package com.redes.udp;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.function.Consumer;
import javazoom.jl.player.Player;

public final class ReproductorAudio implements AutoCloseable {

    private volatile Player player;
    private volatile Thread hilo;

    public synchronized void reproducir(Path archivo, Consumer<String> evento) throws Exception {
        close();
        BufferedInputStream entrada = new BufferedInputStream(new FileInputStream(archivo.toFile()));
        Player nuevo = new Player(entrada);
        player = nuevo;
        // El hilo usa su propia referencia: una reproducción nueva no debe ser tomada por un hilo anterior.
        hilo = new Thread(() -> {
            try {
                evento.accept("Reproduciendo");
                nuevo.play();
                evento.accept("Reproducción finalizada");
            } catch (Exception ex) {
                evento.accept("Reproducción detenida");
            } finally {
                try {
                    entrada.close();
                } catch (Exception ignored) {
                }
            }
        }, "reproductor-jlayer");
        hilo.setDaemon(true);
        hilo.start();
    }

    public void esperar() throws InterruptedException {
        Thread actual = hilo;
        if (actual != null) {
            actual.join();
        }
    }

    @Override
    public synchronized void close() {
        Player actual = player;
        if (actual != null) {
            actual.close();
        }
        player = null;
        hilo = null;
    }
}
