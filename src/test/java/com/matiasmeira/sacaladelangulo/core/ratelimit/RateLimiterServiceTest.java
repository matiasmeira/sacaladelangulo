package com.matiasmeira.sacaladelangulo.core.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RateLimiterService - Tests de balde de tokens")
class RateLimiterServiceTest {

    private final RateLimiterService rateLimiterService = new RateLimiterService();

    @Test
    @DisplayName("tryConsume_PermiteHastaLaCapacidadYLuegoRechaza")
    void tryConsume_PermiteHastaLaCapacidadYLuegoRechaza() {
        String clave = "test:capacidad";

        assertTrue(rateLimiterService.tryConsume(clave, 2, 10_000));
        assertTrue(rateLimiterService.tryConsume(clave, 2, 10_000));
        assertFalse(rateLimiterService.tryConsume(clave, 2, 10_000));
    }

    @Test
    @DisplayName("tryConsume_ClavesDistintasSonIndependientes")
    void tryConsume_ClavesDistintasSonIndependientes() {
        assertTrue(rateLimiterService.tryConsume("test:clave-a", 1, 10_000));
        assertFalse(rateLimiterService.tryConsume("test:clave-a", 1, 10_000));

        // Otra clave no se ve afectada por haber agotado "test:clave-a"
        assertTrue(rateLimiterService.tryConsume("test:clave-b", 1, 10_000));
    }

    @Test
    @DisplayName("tryConsume_SeRecargaConElPasoDelTiempo")
    void tryConsume_SeRecargaConElPasoDelTiempo() throws InterruptedException {
        String clave = "test:recarga";

        assertTrue(rateLimiterService.tryConsume(clave, 1, 150));
        assertFalse(rateLimiterService.tryConsume(clave, 1, 150));

        Thread.sleep(200);

        assertTrue(rateLimiterService.tryConsume(clave, 1, 150));
    }
}
