package com.redes.udp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class PaqueteProtocolo {

    private static final int MAGIC = 0x47424E31; // ASCII: GBN1
    private static final byte VERSION = 1;
    private static final int CABECERA_COMUN = Integer.BYTES + 1 + 1;

    public static final byte SOLICITUD = 1;
    public static final byte META = 2;
    public static final byte META_ACK = 3;
    public static final byte DATOS = 4;
    public static final byte ACK = 5;
    public static final byte FIN = 6;
    public static final byte FIN_ACK = 7;
    public static final byte ERROR = 8;

    public static final byte BANDERA_ULTIMO = 1;
    public static final int LONGITUD_SHA256 = 32;

    private PaqueteProtocolo() {
    }

    public static byte[] solicitud(long token) {
        return nuevoBuffer(SOLICITUD, Long.BYTES).putLong(token).array();
    }

    public static Solicitud leerSolicitud(byte[] datos, int longitud) throws IOException {
        ByteBuffer b = cuerpo(datos, longitud, SOLICITUD, Long.BYTES);
        return new Solicitud(b.getLong());
    }

    public static byte[] metadata(long sesion, long tamanoArchivo, int totalPaquetes,
            String nombreArchivo, byte[] sha256) {
        byte[] nombre = nombreArchivo.getBytes(StandardCharsets.UTF_8);
        if (nombre.length > 500) {
            throw new IllegalArgumentException("El nombre del archivo es demasiado largo");
        }
        if (sha256.length != LONGITUD_SHA256) {
            throw new IllegalArgumentException("SHA-256 debe contener 32 bytes");
        }
        ByteBuffer b = nuevoBuffer(META,
                Long.BYTES + Long.BYTES + Integer.BYTES + Short.BYTES + LONGITUD_SHA256 + nombre.length);
        b.putLong(sesion).putLong(tamanoArchivo).putInt(totalPaquetes);
        b.putShort((short) nombre.length).put(sha256).put(nombre);
        return b.array();
    }

    public static Metadata leerMetadata(byte[] datos, int longitud) throws IOException {
        ByteBuffer b = cuerpoMinimo(datos, longitud, META,
                Long.BYTES + Long.BYTES + Integer.BYTES + Short.BYTES + LONGITUD_SHA256);
        long sesion = b.getLong();
        long tamano = b.getLong();
        int total = b.getInt();
        int longitudNombre = Short.toUnsignedInt(b.getShort());
        byte[] hash = new byte[LONGITUD_SHA256];
        b.get(hash);
        if (tamano < 0 || total <= 0 || longitudNombre < 1 || longitudNombre > b.remaining()) {
            throw new IOException("Metadata inválida");
        }
        byte[] nombre = new byte[longitudNombre];
        b.get(nombre);
        if (b.hasRemaining()) {
            throw new IOException("Metadata contiene bytes inesperados");
        }
        return new Metadata(sesion, tamano, total,
                new String(nombre, StandardCharsets.UTF_8), hash);
    }

    public static byte[] datos(long sesion, int secuencia, boolean ultimo,
            byte[] origen, int offset, int longitud) {
        if (longitud < 0 || longitud > ProtocoloConfig.TAMANO_DATOS_MAX) {
            throw new IllegalArgumentException("Carga útil fuera de rango: " + longitud);
        }
        ByteBuffer b = ByteBuffer.allocate(ProtocoloConfig.TAMANO_CABECERA_DATOS + longitud)
                .order(ByteOrder.BIG_ENDIAN);
        ponerCabecera(b, DATOS);
        b.putLong(sesion).putInt(secuencia)
                .put(ultimo ? BANDERA_ULTIMO : (byte) 0)
                .putShort((short) longitud)
                .put(origen, offset, longitud);
        return b.array();
    }

    public static Datos leerDatos(byte[] datos, int longitud) throws IOException {
        ByteBuffer b = cuerpoMinimo(datos, longitud, DATOS,
                Long.BYTES + Integer.BYTES + 1 + Short.BYTES);
        long sesion = b.getLong();
        int secuencia = b.getInt();
        byte banderas = b.get();
        int longitudCarga = Short.toUnsignedInt(b.getShort());
        if (secuencia < 0 || longitudCarga > ProtocoloConfig.TAMANO_DATOS_MAX
                || longitudCarga != b.remaining()) {
            throw new IOException("Cabecera de datos inválida");
        }
        byte[] carga = new byte[longitudCarga];
        b.get(carga);
        return new Datos(sesion, secuencia, (banderas & BANDERA_ULTIMO) != 0, carga);
    }

    public static byte[] control(byte tipo, long sesion) {
        if (tipo != META_ACK && tipo != FIN && tipo != FIN_ACK) {
            throw new IllegalArgumentException("Tipo de control no permitido: " + tipo);
        }
        return nuevoBuffer(tipo, Long.BYTES).putLong(sesion).array();
    }

    public static long leerControl(byte[] datos, int longitud, byte tipo) throws IOException {
        return cuerpo(datos, longitud, tipo, Long.BYTES).getLong();
    }

    public static byte[] ack(long sesion, int numeroAck) {
        return nuevoBuffer(ACK, Long.BYTES + Integer.BYTES)
                .putLong(sesion).putInt(numeroAck).array();
    }

    public static Ack leerAck(byte[] datos, int longitud) throws IOException {
        ByteBuffer b = cuerpo(datos, longitud, ACK, Long.BYTES + Integer.BYTES);
        return new Ack(b.getLong(), b.getInt());
    }

    public static byte[] error(long sesion, String mensaje) {
        byte[] texto = mensaje.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(texto.length, ProtocoloConfig.TAMANO_MTU - CABECERA_COMUN - Long.BYTES);
        ByteBuffer b = nuevoBuffer(ERROR, Long.BYTES + n).putLong(sesion);
        b.put(texto, 0, n);
        return b.array();
    }

    public static String leerError(byte[] datos, int longitud, long sesionEsperada) throws IOException {
        ByteBuffer b = cuerpoMinimo(datos, longitud, ERROR, Long.BYTES);
        if (b.getLong() != sesionEsperada) {
            throw new IOException("Sesión incorrecta en mensaje de error");
        }
        byte[] texto = new byte[b.remaining()];
        b.get(texto);
        return new String(texto, StandardCharsets.UTF_8);
    }

    public static byte tipo(byte[] datos, int longitud) throws IOException {
        if (longitud < CABECERA_COMUN || longitud > ProtocoloConfig.TAMANO_MTU) {
            throw new IOException("Longitud de datagrama inválida: " + longitud);
        }
        ByteBuffer b = ByteBuffer.wrap(datos, 0, longitud).order(ByteOrder.BIG_ENDIAN);
        if (b.getInt() != MAGIC || b.get() != VERSION) {
            throw new IOException("Datagrama ajeno al protocolo GBN1");
        }
        return b.get();
    }

    private static ByteBuffer nuevoBuffer(byte tipo, int longitudCuerpo) {
        ByteBuffer b = ByteBuffer.allocate(CABECERA_COMUN + longitudCuerpo)
                .order(ByteOrder.BIG_ENDIAN);
        ponerCabecera(b, tipo);
        return b;
    }

    private static void ponerCabecera(ByteBuffer b, byte tipo) {
        b.putInt(MAGIC).put(VERSION).put(tipo);
    }

    private static ByteBuffer cuerpo(byte[] datos, int longitud, byte tipo, int longitudCuerpo)
            throws IOException {
        ByteBuffer b = cuerpoMinimo(datos, longitud, tipo, longitudCuerpo);
        if (b.remaining() != longitudCuerpo) {
            throw new IOException("Longitud inesperada para mensaje tipo " + tipo);
        }
        return b;
    }

    private static ByteBuffer cuerpoMinimo(byte[] datos, int longitud, byte tipo, int minimo)
            throws IOException {
        byte recibido = tipo(datos, longitud);
        if (recibido != tipo) {
            throw new IOException("Se esperaba mensaje " + tipo + " y llegó " + recibido);
        }
        ByteBuffer b = ByteBuffer.wrap(datos, CABECERA_COMUN, longitud - CABECERA_COMUN)
                .order(ByteOrder.BIG_ENDIAN);
        if (b.remaining() < minimo) {
            throw new IOException("Mensaje truncado");
        }
        return b;
    }

    public record Solicitud(long token) {
    }

    public record Metadata(long sesion, long tamanoArchivo, int totalPaquetes,
            String nombreArchivo, byte[] sha256) {
        public Metadata {
            sha256 = Arrays.copyOf(sha256, sha256.length);
        }

        @Override
        public byte[] sha256() {
            return Arrays.copyOf(sha256, sha256.length);
        }
    }

    public record Datos(long sesion, int secuencia, boolean ultimo, byte[] carga) {
        public Datos {
            carga = Arrays.copyOf(carga, carga.length);
        }

        @Override
        public byte[] carga() {
            return Arrays.copyOf(carga, carga.length);
        }
    }

    public record Ack(long sesion, int numero) {
    }
}
