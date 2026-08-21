package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ValidadorFoto - tipo real por magic bytes, tamaño y cantidad")
class ValidadorFotoTest {

    private final ValidadorFoto validadorFoto = new ValidadorFoto();

    /** Cabecera JPEG (FF D8 FF) seguida de relleno. */
    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    /** Cabecera PNG (89 50 4E 47 0D 0A 1A 0A) seguida de relleno. */
    private static byte[] png() {
        byte[] bytes = new byte[64];
        byte[] firma = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(firma, 0, bytes, 0, firma.length);
        return bytes;
    }

    /** "RIFF" en 0..3 y "WEBP" en 8..11, con el tamaño en el medio. */
    private static byte[] webp() {
        byte[] bytes = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, bytes, 8, 4);
        return bytes;
    }

    /** Cabecera de PDF: "%PDF-". */
    private static byte[] pdf() {
        byte[] bytes = new byte[64];
        System.arraycopy("%PDF-".getBytes(), 0, bytes, 0, 5);
        return bytes;
    }

    @Test
    @DisplayName("acepta_jpegPngYWebpValidos")
    void acepta_jpegPngYWebpValidos() {
        assertThatCode(() -> validadorFoto.validar(jpeg(), 0)).doesNotThrowAnyException();
        assertThatCode(() -> validadorFoto.validar(png(), 0)).doesNotThrowAnyException();
        assertThatCode(() -> validadorFoto.validar(webp(), 0)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rechaza_pdfDisfrazadoDeJpeg")
    void rechaza_pdfDisfrazadoDeJpeg() {
        // El nombre y el content-type declarado dirían "image/jpeg"; los bytes no mienten.
        assertThatThrownBy(() -> validadorFoto.validar(pdf(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("imagen");
    }

    @Test
    @DisplayName("rechaza_archivoVacio")
    void rechaza_archivoVacio() {
        assertThatThrownBy(() -> validadorFoto.validar(new byte[0], 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_archivoMasCortoQueLaFirma_sinIndexOutOfBounds")
    void rechaza_archivoMasCortoQueLaFirma_sinIndexOutOfBounds() {
        assertThatThrownBy(() -> validadorFoto.validar(new byte[]{(byte) 0xFF, (byte) 0xD8}, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_riffQueNoEsWebp")
    void rechaza_riffQueNoEsWebp() {
        // Un WAV también empieza con "RIFF": sin chequear también "WEBP" en 8..11 pasaría.
        byte[] wav = new byte[64];
        System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
        System.arraycopy("WAVE".getBytes(), 0, wav, 8, 4);

        assertThatThrownBy(() -> validadorFoto.validar(wav, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rechaza_archivoMayorA5MB")
    void rechaza_archivoMayorA5MB() {
        byte[] gigante = new byte[ValidadorFoto.TAMANIO_MAXIMO_BYTES + 1];
        byte[] firma = jpeg();
        System.arraycopy(firma, 0, gigante, 0, 3);

        assertThatThrownBy(() -> validadorFoto.validar(gigante, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("rechaza_cuandoYaHayElMaximoDeFotos")
    void rechaza_cuandoYaHayElMaximoDeFotos() {
        assertThatThrownBy(() ->
                validadorFoto.validar(jpeg(), ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
    }

    @Test
    @DisplayName("acepta_laDecimaFoto")
    void acepta_laDecimaFoto() {
        assertThatCode(() ->
                validadorFoto.validar(jpeg(), ValidadorFoto.MAXIMO_FOTOS_POR_ESTABLECIMIENTO - 1))
                .doesNotThrowAnyException();
    }
}
