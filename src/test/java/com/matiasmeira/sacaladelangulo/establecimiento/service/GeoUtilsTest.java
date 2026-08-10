package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GeoUtils - Distancia Haversine")
class GeoUtilsTest {

    @Test
    @DisplayName("distanciaKm_MismoPunto_DevuelveCero")
    void distanciaKm_MismoPunto_DevuelveCero() {
        assertEquals(0.0, GeoUtils.distanciaKm(-34.6037, -58.3816, -34.6037, -58.3816), 0.0001);
    }

    @Test
    @DisplayName("distanciaKm_ObeliscoAUshuaia_DevuelveAproximadamente2500Km")
    void distanciaKm_ObeliscoAUshuaia_DevuelveAproximadamente2500Km() {
        double distancia = GeoUtils.distanciaKm(-34.6037, -58.3816, -54.8019, -68.3030);
        assertTrue(distancia > 2300 && distancia < 2500, "Esperaba ~2500km, fue " + distancia);
    }
}
