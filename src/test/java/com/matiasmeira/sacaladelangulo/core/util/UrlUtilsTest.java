package com.matiasmeira.sacaladelangulo.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @ParameterizedTest
    @ValueSource(strings = {
            "/reservar/x?cancha=3&inicio=2026-01-01T10:00:00-03:00",
            "/perfil",
            "/"
    })
    @DisplayName("esRutaInternaSegura_RutaInterna_Acepta")
    void esRutaInternaSegura_RutaInterna_Acepta(String ruta) {
        assertTrue(UrlUtils.esRutaInternaSegura(ruta));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "//x", "/\\x", "http://x", "/a\nb", "/a\tb", "/a\rb", "/a\u0000b"})
    @DisplayName("esRutaInternaSegura_NoEsRutaInterna_Rechaza")
    void esRutaInternaSegura_NoEsRutaInterna_Rechaza(String ruta) {
        assertFalse(UrlUtils.esRutaInternaSegura(ruta));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("esRutaInternaSegura_LimiteDe500Caracteres")
    void esRutaInternaSegura_LimiteDe500Caracteres() {
        assertTrue(UrlUtils.esRutaInternaSegura("/" + "a".repeat(499)));
        assertFalse(UrlUtils.esRutaInternaSegura("/" + "a".repeat(500)));
    }
}
