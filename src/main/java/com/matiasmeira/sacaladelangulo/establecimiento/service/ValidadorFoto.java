package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.springframework.stereotype.Component;

/**
 * Valida el archivo de una foto de establecimiento ANTES de mandarlo a ImageKit.
 *
 * El tipo se determina por los magic bytes del contenido, NO por el content-type que
 * declara la parte multipart ni por la extensión del nombre: los dos los controla el
 * cliente y se falsifican trivialmente. Subir un archivo arbitrario a un CDN público bajo
 * la cuenta del negocio es exactamente lo que esta clase evita.
 */
@Component
public class ValidadorFoto {

    public static final int TAMANIO_MAXIMO_BYTES = 5 * 1024 * 1024;
    public static final int MAXIMO_FOTOS_POR_ESTABLECIMIENTO = 10;

    /** Mínimo de bytes para poder leer la firma más larga (PNG y WebP necesitan 12). */
    private static final int BYTES_MINIMOS = 12;

    public void validar(byte[] contenido, int cantidadDeFotosActuales) {
        if (contenido == null || contenido.length == 0) {
            throw new IllegalArgumentException("El archivo está vacío.");
        }
        if (contenido.length > TAMANIO_MAXIMO_BYTES) {
            throw new IllegalArgumentException("La imagen no puede superar los 5 MB.");
        }
        if (cantidadDeFotosActuales >= MAXIMO_FOTOS_POR_ESTABLECIMIENTO) {
            throw new IllegalArgumentException(
                    "El establecimiento ya tiene el máximo de 10 fotos. Borrá una antes de subir otra.");
        }
        if (contenido.length < BYTES_MINIMOS || !esImagenSoportada(contenido)) {
            throw new IllegalArgumentException("El archivo no es una imagen JPEG, PNG ni WebP.");
        }
    }

    private boolean esImagenSoportada(byte[] bytes) {
        return esJpeg(bytes) || esPng(bytes) || esWebp(bytes);
    }

    private boolean esJpeg(byte[] bytes) {
        return (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean esPng(byte[] bytes) {
        byte[] firma = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        return empiezaCon(bytes, firma, 0);
    }

    /**
     * "RIFF" en 0..3 y "WEBP" en 8..11. Los 4 bytes del medio son el tamaño del archivo.
     * Hay que mirar los dos bloques: un WAV o un AVI también empiezan con "RIFF".
     */
    private boolean esWebp(byte[] bytes) {
        return empiezaCon(bytes, "RIFF".getBytes(), 0) && empiezaCon(bytes, "WEBP".getBytes(), 8);
    }

    private boolean empiezaCon(byte[] bytes, byte[] firma, int desde) {
        if (bytes.length < desde + firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (bytes[desde + i] != firma[i]) {
                return false;
            }
        }
        return true;
    }
}
