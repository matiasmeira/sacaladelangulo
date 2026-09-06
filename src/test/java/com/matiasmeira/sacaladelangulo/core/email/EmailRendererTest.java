package com.matiasmeira.sacaladelangulo.core.email;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmailRenderer - Motor de plantillas Thymeleaf")
class EmailRendererTest {

    @Test
    @DisplayName("render_PlantillaExistente_DevuelveHtmlConLaVariableInterpolada")
    void render_PlantillaExistente_DevuelveHtmlConLaVariableInterpolada() {
        EmailRenderer emailRenderer = new EmailRenderer();

        String html = emailRenderer.render("cuenta-eliminada", Map.of("nombre", "Juan"));

        assertTrue(html.contains("Juan"));
    }

    /**
     * Mismo modelo que arma ReservaNotificacionListener.construirModeloTurnoFijo para un
     * turno fijo de los martes 20:00 con 3 ocurrencias.
     */
    private Map<String, Object> modeloDeTurnoFijo() {
        return Map.of(
                "establecimientoNombre", "Complejo Test",
                "canchaNombre", "Cancha A",
                "deporte", Deporte.FUTBOL_5,
                "horaInicio", "20:00",
                "horaFin", "21:00",
                "fechas", List.of("08/01/2030", "15/01/2030", "22/01/2030"),
                "cantidadTurnos", 3,
                "precioPorTurno", BigDecimal.valueOf(1500),
                "precioTotalTurnoFijo", BigDecimal.valueOf(4500),
                "nombreCliente", "Juan Pérez");
    }

    @Test
    @DisplayName("render_TurnoFijoConfirmado_ListaTodasLasFechasDelPeriodo")
    void render_TurnoFijoConfirmado_ListaTodasLasFechasDelPeriodo() {
        EmailRenderer emailRenderer = new EmailRenderer();

        String html = emailRenderer.render("turno-fijo-confirmado", modeloDeTurnoFijo());

        // Un aviso único en lugar de uno por ocurrencia sólo es aceptable si el jugador
        // puede ver TODAS sus fechas en él.
        assertTrue(html.contains("08/01/2030"), "falta la 1ª fecha");
        assertTrue(html.contains("15/01/2030"), "falta la 2ª fecha");
        assertTrue(html.contains("22/01/2030"), "falta la 3ª fecha");
        assertTrue(html.contains("Cancha A"));
        assertTrue(html.contains("Complejo Test"));
        assertTrue(html.contains("20:00"));
        assertTrue(html.contains("21:00"));
        assertTrue(html.contains("4500"), "falta el total del período");
    }

    @Test
    @DisplayName("render_TurnoFijoNuevoDueno_ListaTodasLasFechasYElCliente")
    void render_TurnoFijoNuevoDueno_ListaTodasLasFechasYElCliente() {
        EmailRenderer emailRenderer = new EmailRenderer();

        String html = emailRenderer.render("turno-fijo-nuevo-dueno", modeloDeTurnoFijo());

        assertTrue(html.contains("Juan Pérez"), "falta el cliente");
        assertTrue(html.contains("08/01/2030"), "falta la 1ª fecha");
        assertTrue(html.contains("15/01/2030"), "falta la 2ª fecha");
        assertTrue(html.contains("22/01/2030"), "falta la 3ª fecha");
        assertTrue(html.contains("Cancha A"));
        assertTrue(html.contains("4500"), "falta el total del período");
    }
}
