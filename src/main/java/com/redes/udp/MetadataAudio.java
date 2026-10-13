package com.redes.udp;

import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;
import java.nio.file.Path;

public final class MetadataAudio {

    private MetadataAudio() {
    }

    public static Info leer(Path archivo) throws Exception {
        Mp3File mp3 = new Mp3File(archivo);
        String tipo = "Sin ID3";
        String titulo = null;
        String artista = null;
        String album = null;
        String anio = null;
        if (mp3.hasId3v2Tag()) {
            ID3v2 tag = mp3.getId3v2Tag();
            tipo = "ID3v2";
            titulo = tag.getTitle();
            artista = tag.getArtist();
            album = tag.getAlbum();
            anio = tag.getYear();
        } else if (mp3.hasId3v1Tag()) {
            ID3v1 tag = mp3.getId3v1Tag();
            tipo = "ID3v1";
            titulo = tag.getTitle();
            artista = tag.getArtist();
            album = tag.getAlbum();
            anio = tag.getYear();
        }
        return new Info(valor(titulo), valor(artista), valor(album), valor(anio), tipo,
                mp3.getLengthInSeconds(), mp3.getBitrate(), mp3.getSampleRate());
    }

    private static String valor(String valor) {
        return valor == null || valor.isBlank() ? "No especificado" : valor.trim();
    }

    public record Info(String titulo, String artista, String album, String anio,
            String tipoEtiqueta, long duracionSegundos, int bitrate, int frecuencia) {
    }
}
