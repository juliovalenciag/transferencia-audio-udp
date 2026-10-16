package com.redes.udp;

public final class ProtocoloConfig {

    private ProtocoloConfig() {
    }

    public static final String HOST_SERVIDOR = "127.0.0.1";
    public static final int PUERTO_SERVIDOR = 9876;

    public static final int TAMANO_MTU = 1024;
    public static final int TAMANO_CABECERA_DATOS = 21;
    public static final int TAMANO_DATOS_MAX = TAMANO_MTU - TAMANO_CABECERA_DATOS;

    public static final int TAMANO_VENTANA = 5;
    public static final int SO_TIMEOUT_MS = 1200;
    public static final int MAX_REINTENTOS_CONTROL = 10;
    public static final int TIMEOUT_INACTIVIDAD_CLIENTE_MS = 15_000;
    public static final int TIMEOUT_SIN_RESPUESTA_SERVIDOR_MS = 30_000;

    public static final String ARCHIVO_ORIGEN = "cancion1.mp3";
    public static final String ARCHIVO_DESTINO = "cancion1-recibida.mp3";
}
