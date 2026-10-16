package com.redes.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

public final class ServidorTransferencia implements AutoCloseable {

    private final Path archivo;
    private final int puerto;
    private final int tamanoVentana;
    private final int timeoutMs;
    private final double probabilidadPerdida;
    private final EscuchaTransferencia escucha;

    private volatile boolean cancelado;
    private volatile DatagramSocket socket;
    private long datagramas;
    private long retransmisiones;

    public ServidorTransferencia(Path archivo, int puerto, int tamanoVentana,
            int timeoutMs, double probabilidadPerdida, EscuchaTransferencia escucha) {
        if (tamanoVentana < 1 || timeoutMs < 100 || puerto < 1 || puerto > 65535) {
            throw new IllegalArgumentException("Parámetros de servidor fuera de rango");
        }
        if (probabilidadPerdida < 0 || probabilidadPerdida > 1) {
            throw new IllegalArgumentException("La pérdida debe estar entre 0 y 1");
        }
        this.archivo = archivo;
        this.puerto = puerto;
        this.tamanoVentana = tamanoVentana;
        this.timeoutMs = timeoutMs;
        this.probabilidadPerdida = probabilidadPerdida;
        this.escucha = escucha == null ? EscuchaTransferencia.CONSOLA : escucha;
    }

    public Resultado ejecutar() throws IOException {
        validarArchivo();
        byte[] contenido = Files.readAllBytes(archivo);
        byte[] hash = sha256(contenido);
        int totalPaquetes = Math.max(1, (contenido.length + ProtocoloConfig.TAMANO_DATOS_MAX - 1)
                / ProtocoloConfig.TAMANO_DATOS_MAX);
        long sesion = ThreadLocalRandom.current().nextLong();

        escucha.estado("Esperando cliente");
        escucha.log("Servidor UDP listo en el puerto " + puerto);
        escucha.log("Archivo: " + archivo.getFileName() + " · " + contenido.length + " bytes");
        escucha.log("SHA-256: " + HexFormat.of().formatHex(hash));

        long inicio = System.nanoTime();
        try (DatagramSocket ds = new DatagramSocket(puerto)) {
            socket = ds;
            ClienteRemoto cliente = esperarSolicitud(ds);
            comprobarCancelacion();
            escucha.log("Solicitud válida desde " + cliente.direccion().getHostAddress()
                    + ":" + cliente.puerto());

            byte[] meta = PaqueteProtocolo.metadata(sesion, contenido.length, totalPaquetes,
                    archivo.getFileName().toString(), hash);
            negociarMetadata(ds, cliente, meta, sesion);
            escucha.estado("Transmitiendo");

            byte[][] paquetes = fragmentar(contenido, sesion, totalPaquetes);
            enviarGoBackN(ds, cliente, paquetes, contenido.length, sesion);
            finalizar(ds, cliente, sesion);

            long duracionMs = (System.nanoTime() - inicio) / 1_000_000;
            escucha.progreso(contenido.length, contenido.length);
            escucha.estado("Transferencia completa");
            escucha.log("Transferencia confirmada en " + duracionMs + " ms");
            return new Resultado(totalPaquetes, datagramas, retransmisiones,
                    contenido.length, duracionMs, hash);
        } catch (SocketException ex) {
            if (cancelado) {
                throw new IOException("Servidor detenido por el usuario", ex);
            }
            throw ex;
        } finally {
            socket = null;
        }
    }

    private ClienteRemoto esperarSolicitud(DatagramSocket ds) throws IOException {
        byte[] buffer = new byte[ProtocoloConfig.TAMANO_MTU];
        while (true) {
            comprobarCancelacion();
            DatagramPacket p = new DatagramPacket(buffer, buffer.length);
            ds.receive(p);
            try {
                PaqueteProtocolo.Solicitud solicitud = PaqueteProtocolo.leerSolicitud(
                        p.getData(), p.getLength());
                return new ClienteRemoto(p.getAddress(), p.getPort(), solicitud.token());
            } catch (IOException ex) {
                escucha.log("Datagrama inicial descartado: " + ex.getMessage());
            }
        }
    }

    private void negociarMetadata(DatagramSocket ds, ClienteRemoto cliente, byte[] meta, long sesion)
            throws IOException {
        ds.setSoTimeout(timeoutMs);
        for (int intento = 1; intento <= ProtocoloConfig.MAX_REINTENTOS_CONTROL; intento++) {
            enviar(ds, cliente, meta, false, "META");
            escucha.log("Oferta de archivo enviada (intento " + intento + ")");
            try {
                DatagramPacket p = recibir(ds);
                if (!esCliente(p, cliente)) {
                    continue;
                }
                byte tipo = PaqueteProtocolo.tipo(p.getData(), p.getLength());
                if (tipo == PaqueteProtocolo.META_ACK
                        && PaqueteProtocolo.leerControl(p.getData(), p.getLength(), tipo) == sesion) {
                    escucha.log("Cliente aceptó la metadata; inicia Go-Back-N");
                    return;
                }
                // Si se perdió META, el cliente puede repetir su solicitud.
                if (tipo == PaqueteProtocolo.SOLICITUD) {
                    continue;
                }
            } catch (SocketTimeoutException ex) {
                escucha.log("SO_TIMEOUT esperando META_ACK");
            } catch (IOException ex) {
                escucha.log("Control inválido descartado: " + ex.getMessage());
            }
        }
        throw new IOException("El cliente no confirmó la metadata");
    }

    private void enviarGoBackN(DatagramSocket ds, ClienteRemoto cliente, byte[][] paquetes,
            long totalBytes, long sesion) throws IOException {
        int base = 0;
        int siguiente = 0;
        long inicioTemporizador = 0;
        long ultimaRespuesta = System.nanoTime();
        boolean[] enviados = new boolean[paquetes.length];

        while (base < paquetes.length) {
            comprobarCancelacion();

            while (siguiente < paquetes.length && siguiente < base + tamanoVentana) {
                if (base == siguiente) {
                    inicioTemporizador = System.nanoTime();
                }
                boolean retransmision = enviados[siguiente];
                enviar(ds, cliente, paquetes[siguiente], retransmision, "DATA #" + siguiente);
                enviados[siguiente] = true;
                siguiente++;
                escucha.ventana(base, siguiente, paquetes.length, tamanoVentana);
            }

            long transcurrido = (System.nanoTime() - inicioTemporizador) / 1_000_000;
            int restante = (int) Math.max(1, timeoutMs - transcurrido);
            ds.setSoTimeout(restante); // requisito: temporizador mediante SO_TIMEOUT

            try {
                DatagramPacket p = recibir(ds);
                if (!esCliente(p, cliente)) {
                    continue;
                }
                byte tipo = PaqueteProtocolo.tipo(p.getData(), p.getLength());
                if (tipo == PaqueteProtocolo.ERROR) {
                    throw new IOException("Cliente canceló: "
                            + PaqueteProtocolo.leerError(p.getData(), p.getLength(), sesion));
                }
                if (tipo != PaqueteProtocolo.ACK) {
                    continue;
                }
                PaqueteProtocolo.Ack ack = PaqueteProtocolo.leerAck(p.getData(), p.getLength());
                if (ack.sesion() != sesion || ack.numero() >= siguiente) {
                    escucha.log("ACK inválido descartado");
                    continue;
                }
                ultimaRespuesta = System.nanoTime(); // incluso un ACK duplicado prueba que el cliente sigue activo
                if (ack.numero() >= base) {
                    int baseAnterior = base;
                    base = ack.numero() + 1; // ACK acumulativo
                    long confirmados = Math.min(totalBytes,
                            (long) base * ProtocoloConfig.TAMANO_DATOS_MAX);
                    escucha.log("ACK " + ack.numero() + " · base " + baseAnterior + " → " + base);
                    escucha.progreso(confirmados, totalBytes);
                    escucha.ventana(base, siguiente, paquetes.length, tamanoVentana);
                    if (base < siguiente) {
                        inicioTemporizador = System.nanoTime();
                    }
                } else {
                    escucha.log("ACK duplicado " + ack.numero() + " ignorado");
                }
            } catch (SocketTimeoutException ex) {
                if ((System.nanoTime() - ultimaRespuesta) / 1_000_000
                        > ProtocoloConfig.TIMEOUT_SIN_RESPUESTA_SERVIDOR_MS) {
                    throw new IOException("El cliente dejó de responder durante "
                            + ProtocoloConfig.TIMEOUT_SIN_RESPUESTA_SERVIDOR_MS / 1000 + " segundos");
                }
                escucha.log("SO_TIMEOUT · Retroceder-N desde paquete " + base);
                siguiente = base;
                inicioTemporizador = System.nanoTime();
                escucha.ventana(base, siguiente, paquetes.length, tamanoVentana);
            } catch (IOException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith("Cliente canceló")) {
                    throw ex;
                }
                escucha.log("Datagrama de respuesta descartado: " + ex.getMessage());
            }
        }
    }

    private void finalizar(DatagramSocket ds, ClienteRemoto cliente, long sesion) throws IOException {
        byte[] fin = PaqueteProtocolo.control(PaqueteProtocolo.FIN, sesion);
        ds.setSoTimeout(timeoutMs);
        for (int intento = 1; intento <= ProtocoloConfig.MAX_REINTENTOS_CONTROL; intento++) {
            enviar(ds, cliente, fin, intento > 1, "FIN");
            try {
                DatagramPacket p = recibir(ds);
                if (esCliente(p, cliente)
                        && PaqueteProtocolo.tipo(p.getData(), p.getLength()) == PaqueteProtocolo.FIN_ACK
                        && PaqueteProtocolo.leerControl(p.getData(), p.getLength(),
                                PaqueteProtocolo.FIN_ACK) == sesion) {
                    escucha.log("FIN_ACK recibido; sesión cerrada de forma explícita");
                    return;
                }
            } catch (SocketTimeoutException ex) {
                escucha.log("SO_TIMEOUT esperando FIN_ACK");
            } catch (IOException ex) {
                escucha.log("Respuesta final inválida: " + ex.getMessage());
            }
        }
        throw new IOException("No se recibió FIN_ACK; los datos sí fueron confirmados");
    }

    private void enviar(DatagramSocket ds, ClienteRemoto cliente, byte[] datos,
            boolean retransmision, String etiqueta) throws IOException {
        comprobarCancelacion();
        datagramas++;
        if (retransmision) {
            retransmisiones++;
        }
        boolean perder = etiqueta.startsWith("DATA") && probabilidadPerdida > 0
                && ThreadLocalRandom.current().nextDouble() < probabilidadPerdida;
        if (perder) {
            escucha.log("PÉRDIDA simulada · " + etiqueta);
        } else {
            ds.send(new DatagramPacket(datos, datos.length, cliente.direccion(), cliente.puerto()));
            if (etiqueta.startsWith("DATA")) {
                escucha.log((retransmision ? "Reenviado " : "Enviado ") + etiqueta);
            }
        }
        escucha.estadisticas(datagramas, retransmisiones, 0);
    }

    private static DatagramPacket recibir(DatagramSocket ds) throws IOException {
        byte[] buffer = new byte[ProtocoloConfig.TAMANO_MTU];
        DatagramPacket p = new DatagramPacket(buffer, buffer.length);
        ds.receive(p);
        return p;
    }

    private static boolean esCliente(DatagramPacket p, ClienteRemoto cliente) {
        return p.getPort() == cliente.puerto() && p.getAddress().equals(cliente.direccion());
    }

    private static byte[][] fragmentar(byte[] contenido, long sesion, int total) {
        byte[][] paquetes = new byte[total][];
        for (int i = 0; i < total; i++) {
            int offset = i * ProtocoloConfig.TAMANO_DATOS_MAX;
            int longitud = Math.min(ProtocoloConfig.TAMANO_DATOS_MAX,
                    Math.max(0, contenido.length - offset));
            paquetes[i] = PaqueteProtocolo.datos(sesion, i, i == total - 1,
                    contenido, Math.min(offset, contenido.length), longitud);
        }
        return paquetes;
    }

    private void validarArchivo() throws IOException {
        if (!Files.isRegularFile(archivo) || !Files.isReadable(archivo)) {
            throw new IOException("No se puede leer el archivo: " + archivo.toAbsolutePath());
        }
        if (!archivo.getFileName().toString().toLowerCase().endsWith(".mp3")) {
            throw new IOException("El archivo de origen debe tener extensión .mp3");
        }
        if (Files.size(archivo) > Integer.MAX_VALUE) {
            throw new IOException("El archivo excede el límite de esta implementación académica (2 GiB)");
        }
    }

    private static byte[] sha256(byte[] datos) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(datos);
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 no está disponible", ex);
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

    private record ClienteRemoto(InetAddress direccion, int puerto, long token) {
    }

    public record Resultado(int paquetes, long datagramas, long retransmisiones,
            long bytes, long duracionMs, byte[] sha256) {
    }
}
