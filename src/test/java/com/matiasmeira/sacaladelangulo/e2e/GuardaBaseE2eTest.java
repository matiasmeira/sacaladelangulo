package com.matiasmeira.sacaladelangulo.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardaBaseE2eTest {

    @Test
    void aceptaBaseE2e() {
        assertDoesNotThrow(() -> GuardaBaseE2e.verificar("jdbc:postgresql://localhost:5432/sacaladelangulo_e2e"));
    }

    @Test
    void aceptaBaseE2eConParametros() {
        assertDoesNotThrow(() -> GuardaBaseE2e.verificar("jdbc:postgresql://localhost:5432/sacaladelangulo_e2e?sslmode=disable"));
    }

    @Test
    void rechazaLaBaseDeDesarrollo() {
        var e = assertThrows(IllegalStateException.class,
                () -> GuardaBaseE2e.verificar("jdbc:postgresql://localhost:5432/sacaladelangulo"));
        assertTrue(e.getMessage().contains("_e2e"));
    }

    @Test
    void rechazaUnaBaseQueSoloContieneE2eEnElMedio() {
        assertThrows(IllegalStateException.class,
                () -> GuardaBaseE2e.verificar("jdbc:postgresql://localhost:5432/sacaladelangulo_e2e_backup"));
    }

    @Test
    void rechazaNull() {
        assertThrows(IllegalStateException.class, () -> GuardaBaseE2e.verificar(null));
    }
}
