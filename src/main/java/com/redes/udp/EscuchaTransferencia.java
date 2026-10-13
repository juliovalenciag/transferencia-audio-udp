package com.redes.udp;

public interface EscuchaTransferencia {

    EscuchaTransferencia CONSOLA = new EscuchaTransferencia() {
        @Override
        public void log(String mensaje) {
            System.out.println(mensaje);
        }
    };

    default void log(String mensaje) {
    }

    default void estado(String estado) {
    }

    default void progreso(long actual, long total) {
    }

    default void ventana(int base, int siguiente, int total, int tamanoVentana) {
    }

    default void estadisticas(long datagramas, long retransmisiones, long bytes) {
    }
}
