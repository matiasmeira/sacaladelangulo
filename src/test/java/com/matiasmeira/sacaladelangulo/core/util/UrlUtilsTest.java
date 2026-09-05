package com.matiasmeira.sacaladelangulo.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("UrlUtils - Normalización de URLs base")
class UrlUtilsTest {

    @Test
    @DisplayName("quitarSlashFinal_ConSlashFinal_LoQuita")
    void quitarSlashFinal_ConSlashFinal_LoQuita() {
        assertEquals("https://canche.ar", UrlUtils.quitarSlashFinal("https://canche.ar/"));
    }

    @Test
    @DisplayName("quitarSlashFinal_SinSlashFinal_NoCambia")
    void quitarSlashFinal_SinSlashFinal_NoCambia() {
        assertEquals("http://localhost:3000", UrlUtils.quitarSlashFinal("http://localhost:3000"));
    }
}
