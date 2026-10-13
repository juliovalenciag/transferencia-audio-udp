package com.redes.udp;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;


public final class ClienteTransferencia implements AutoCloseable {

    private final String host;
    private final int puerto;
    private final Path destino;
    private final double probabilidadPerdidaAck;
    private final EscuchaTransferencia escucha;

    private volatile boolean cancelado;
    private volatile DatagramSocket socket;
    private long datagramas;
    private long bytesRecibidos;

    public ClienteTransferencia(String host, int puerto, Path destino,
            double probabilidadPerdidaAck, EscuchaTransferencia escucha) {
        if (host == null || host.isBlank() || puerto < 1 || puerto > 65535) {
            throw new IllegalArgumentException("Host o puerto inválido");
        }
        if (probabilidadPerdidaAck < 0 || probabilidadPerdidaAck > 1) {
            throw new IllegalArgumentException("La pérdida de ACK debe estar entre 0 y 1");
        }
        this.host = host.trim();
        this.puerto = puerto;
        this.destino = destino;
        this.probabilidadPerdidaAck = probabilidadPerdidaAck;
        this.escucha = escucha == null ? EscuchaTransferencia.CONSOLA : escucha;
    }

    public Resultado ejecutar() throws IOException {
        InetAddress direccionServidor = InetAddress.getByName(host);
        InetSocketAddress servidor = new InetSocketAddress(direccionServidor, puerto);
        long token = ThreadLocalRandom.current().nextLong();
        long inicio = System.nanoTime();
        Path temporal = destino.resolveSibling(destino.getFileName() + ".part");
        PaqueteProtocolo.Metadata meta = null;
        boolean publicado = false;

        Path padre = destino.toAbsolutePath().getParent();
        if (padre != null) {
            Files.createDirectories(padre);
        }
        Files.deleteIfExists(temporal);

        escucha.estado("Conectando");
        escucha.log("Destino: " + destino.toAbsolutePath());
        try (DatagramSocket ds = new DatagramSocket()) {
            socket = ds;
            meta = solicitarMetadata(ds, servidor, token);
            validarMetadata(meta);
            enviarControl(ds, servidor, PaqueteProtocolo.META_ACK, meta.sesion());

            escucha.estado("Recibiendo");
            escucha.log("Oferta: " + meta.nombreArchivo() + " · " + meta.tamanoArchivo()
                    + " bytes · " + meta.totalPaquetes() + " paquetes");
            escucha.log("SHA-256 esperado: " + HexFormat.of().formatHex(meta.sha256()));

            MessageDigest digest = nuevoSha256();
            int esperado = 0;
            boolean ultimoRecibido = false;
            ds.setSoTimeout(ProtocoloConfig.TIMEOUT_INACTIVIDAD_CLIENTE_MS);

            try (OutputStream salida = new BufferedOutputStream(Files.newOutputStream(temporal))) {
                while (esperado < meta.totalPaquetes()) {
                    comprobarCancelacion();
                    DatagramPacket p = recibir(ds);
                    if (!esServidor(p, servidor)) {
                        escucha.log("Datagrama de origen desconocido descartado");
                        continue;
                    }
                    try {
                        byte tipo = PaqueteProtocolo.tipo(p.getData(), p.getLength());
                        if (tipo == PaqueteProtocolo.META) {
                            // El META_ACK original pudo perderse: confirmar otra vez.
                            PaqueteProtocolo.Metadata repetida = PaqueteProtocolo.leerMetadata(
                                    p.getData(), p.getLength());
                            if (repetida.sesion() == meta.sesion()) {
                                enviarControl(ds, servidor, PaqueteProtocolo.META_ACK, meta.sesion());
                                escucha.log("Metadata repetida · META_ACK reenviado");
                            }
                            continue;
                        }
                        if (tipo != PaqueteProtocolo.DATOS) {
                            continue;
                        }
                        PaqueteProtocolo.Datos datos = PaqueteProtocolo.leerDatos(
                                p.getData(), p.getLength());
                        if (datos.sesion() != meta.sesion()) {
                            continue;
                        }

                        if (datos.secuencia() == esperado) {
                            boolean debeSerUltimo = esperado == meta.totalPaquetes() - 1;
                            if (datos.ultimo() != debeSerUltimo) {
                                throw new IOException("Bandera de último paquete inconsistente");
                            }
                            byte[] carga = datos.carga();
                            if (bytesRecibidos + carga.length > meta.tamanoArchivo()) {
                                throw new IOException("La carga excede el tamaño anunciado");
                            }
                            salida.write(carga);
                            digest.update(carga);
                            bytesRecibidos += carga.length;
                            ultimoRecibido = datos.ultimo();
                            enviarAck(ds, servidor, meta.sesion(), esperado);
                            escucha.log("Paquete #" + esperado + " en orden · ACK " + esperado);
                            esperado++;
                            datagramas++;
                            escucha.progreso(bytesRecibidos, meta.tamanoArchivo());
                            escucha.ventana(esperado, esperado, meta.totalPaquetes(), 1);
                            escucha.estadisticas(datagramas, 0, bytesRecibidos);
                        } else {
                            escucha.log("Paquete #" + datos.secuencia() + " descartado; se esperaba #" + esperado);
                            // ACK acumulativo del último paquete aceptado, incluso -1.
                            enviarAck(ds, servidor, meta.sesion(), esperado - 1);
                        }
                    } catch (IOException ex) {
                        escucha.log("Datagrama inválido descartado: " + ex.getMessage());
                    }
                }
            } catch (SocketTimeoutException ex) {
                throw new IOException("El servidor dejó de enviar datos durante 15 segundos", ex);
            }

            if (!ultimoRecibido || bytesRecibidos != meta.tamanoArchivo()) {
                notificarError(ds, servidor, meta.sesion(), "Tamaño o fin de archivo inconsistente");
                throw new IOException("El tamaño recibido no coincide con la metadata");
            }
            byte[] hashReal = digest.digest();
            if (!Arrays.equals(hashReal, meta.sha256())) {
                notificarError(ds, servidor, meta.sesion(), "SHA-256 no coincide");
                throw new IOException("Falló la verificación SHA-256");
            }
            escucha.log("Integridad SHA-256 verificada");

            esperarFin(ds, servidor, meta.sesion(), meta.totalPaquetes() - 1);
            moverAtomico(temporal, destino);
            publicado = true;

            long duracionMs = (System.nanoTime() - inicio) / 1_000_000;
            escucha.progreso(bytesRecibidos, bytesRecibidos);
            escucha.estado("Archivo listo");
            escucha.log("Archivo publicado solo después de completar y verificar la recepción");
            return new Resultado(destino, meta.nombreArchivo(), meta.totalPaquetes(),
                    bytesRecibidos, duracionMs, hashReal);
        } catch (SocketException ex) {
            if (cancelado) {
                throw new IOException("Recepción cancelada por el usuario", ex);
            }
            throw ex;
        } finally {
            socket = null;
            if (!publicado) {
                Files.deleteIfExists(temporal);
            }
        }
    }

    private PaqueteProtocolo.Metadata solicitarMetadata(DatagramSocket ds,
            InetSocketAddress servidor, long token) throws IOException {
        byte[] solicitud = PaqueteProtocolo.solicitud(token);
        ds.setSoTimeout(ProtocoloConfig.SO_TIMEOUT_MS);
        for (int intento = 1; intento <= ProtocoloConfig.MAX_REINTENTOS_CONTROL; intento++) {
            ds.send(new DatagramPacket(solicitud, solicitud.length, servidor));
            escucha.log("Solicitud enviada (intento " + intento + ")");
            try {
                DatagramPacket p = recibir(ds);
                if (esServidor(p, servidor)
                        && PaqueteProtocolo.tipo(p.getData(), p.getLength()) == PaqueteProtocolo.META) {
                    return PaqueteProtocolo.leerMetadata(p.getData(), p.getLength());
                }
            } catch (SocketTimeoutException ex) {
                escucha.log("Sin respuesta; se reintentará la solicitud");
            } catch (IOException ex) {
                escucha.log("Respuesta inicial inválida: " + ex.getMessage());
            }
        }
        throw new IOException("No se recibió respuesta del servidor");
    }

    private void esperarFin(DatagramSocket ds, InetSocketAddress servidor,
            long sesion, int ultimoAck) throws IOException {
        escucha.estado("Verificando cierre");
        ds.setSoTimeout(ProtocoloConfig.TIMEOUT_INACTIVIDAD_CLIENTE_MS);
        while (true) {
            DatagramPacket p;
            try {
                p = recibir(ds);
            } catch (SocketTimeoutException ex) {
                throw new IOException("No se recibió FIN del servidor", ex);
            }
            if (!esServidor(p, servidor)) {
                continue;
            }
            try {
                byte tipo = PaqueteProtocolo.tipo(p.getData(), p.getLength());
                if (tipo == PaqueteProtocolo.FIN
                        && PaqueteProtocolo.leerControl(p.getData(), p.getLength(), tipo) == sesion) {
                    // Redundancia pequeña para que la terminación sea resistente a pérdida.
                    for (int i = 0; i < 3; i++) {
                        enviarControl(ds, servidor, PaqueteProtocolo.FIN_ACK, sesion);
                    }
                    escucha.log("FIN recibido · FIN_ACK enviado");
                    return;
                }
                if (tipo == PaqueteProtocolo.DATOS) {
                    PaqueteProtocolo.Datos repetido = PaqueteProtocolo.leerDatos(
                            p.getData(), p.getLength());
                    if (repetido.sesion() == sesion) {
                        // El ACK final pudo perderse: se confirma de nuevo sin reescribir.
                        enviarAck(ds, servidor, sesion, ultimoAck);
                        escucha.log("Último paquete repetido · ACK final reenviado");
                    }
                }
            } catch (IOException ex) {
                escucha.log("Control final inválido: " + ex.getMessage());
            }
        }
    }

    private void enviarAck(DatagramSocket ds, InetSocketAddress servidor,
            long sesion, int ack) throws IOException {
        if (probabilidadPerdidaAck > 0
                && ThreadLocalRandom.current().nextDouble() < probabilidadPerdidaAck) {
            escucha.log("PÉRDIDA simulada · ACK " + ack);
            return;
        }
        byte[] datos = PaqueteProtocolo.ack(sesion, ack);
        ds.send(new DatagramPacket(datos, datos.length, servidor));
    }

    private static void enviarControl(DatagramSocket ds, InetSocketAddress servidor,
            byte tipo, long sesion) throws IOException {
        byte[] datos = PaqueteProtocolo.control(tipo, sesion);
        ds.send(new DatagramPacket(datos, datos.length, servidor));
    }

    private static void notificarError(DatagramSocket ds, InetSocketAddress servidor,
            long sesion, String mensaje) {
        try {
            byte[] datos = PaqueteProtocolo.error(sesion, mensaje);
            ds.send(new DatagramPacket(datos, datos.length, servidor));
        } catch (IOException ignored) {
            // El error original es más útil que un fallo al notificarlo.
        }
    }

    private static DatagramPacket recibir(DatagramSocket ds) throws IOException {
        byte[] buffer = new byte[ProtocoloConfig.TAMANO_MTU];
        DatagramPacket p = new DatagramPacket(buffer, buffer.length);
        ds.receive(p);
        return p;
    }

    private static boolean esServidor(DatagramPacket p, InetSocketAddress servidor) {
        return p.getPort() == servidor.getPort() && p.getAddress().equals(servidor.getAddress());
    }

    private static MessageDigest nuevoSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 no está disponible", ex);
        }
    }

    private static void validarMetadata(PaqueteProtocolo.Metadata meta) throws IOException {
        long calculado = Math.max(1, (meta.tamanoArchivo() + ProtocoloConfig.TAMANO_DATOS_MAX - 1)
                / ProtocoloConfig.TAMANO_DATOS_MAX);
        if (meta.totalPaquetes() != calculado) {
            throw new IOException("Cantidad de paquetes incompatible con el tamaño anunciado");
        }
        if (!meta.nombreArchivo().toLowerCase().endsWith(".mp3")) {
            throw new IOException("El servidor no ofreció un archivo MP3");
        }
    }

    private static void moverAtomico(Path origen, Path destino) throws IOException {
        try {
            Files.move(origen, destino, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void comprobarCancelacion() throws IOException {
        if (cancelado) {
            throw new IOException("Operación cancelada por el usuario");
        }
    }

    @Override
    public void close() {
        cancelado = true;
        DatagramSocket actual = socket;
        if (actual != null) {
            actual.close();
        }
    }

    public record Resultado(Path archivo, String nombreOriginal, int paquetes,
            long bytes, long duracionMs, byte[] sha256) {
    }
}
