package com.matiasmeira.sacaladelangulo.core.trazabilidad;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lo que el test de integración no puede observar: qué hay en el MDC DURANTE la cadena, y
 * que no quede nada DESPUÉS. La limpieza importa porque los hilos del contenedor se
 * reutilizan: un traceId filtrado correlacionaría líneas de dos requests distintas, que
 * es peor que no tener traza.
 */
@DisplayName("TraceIdFilter - manejo del MDC")
class TraceIdFilterTest {

    private final TraceIdFilter filtro = new TraceIdFilter();

    @AfterEach
    void limpiarMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("elMdcTieneElTraceId_mientrasCorreLaCadena")
    void elMdcTieneElTraceId_mientrasCorreLaCadena() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> visto = new AtomicReference<>();
        FilterChain cadena = (req, res) -> visto.set(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));

        filtro.doFilter(new MockHttpServletRequest(), response, cadena);

        assertNotNull(visto.get(), "El MDC deberia tener el traceId durante la cadena");
        assertEquals(response.getHeader(TraceIdFilter.TRACE_ID_HEADER), visto.get(),
                "El traceId del MDC y el del header deberian ser el mismo");
    }

    @Test
    @DisplayName("elMdcQuedaLimpio_despuesDeLaRequest")
    void elMdcQuedaLimpio_despuesDeLaRequest() throws Exception {
        filtro.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

        assertNull(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
                "El traceId no deberia sobrevivir a la request: el hilo se reutiliza");
    }

    @Test
    @DisplayName("elMdcQuedaLimpio_aunqueLaCadenaExplote")
    void elMdcQuedaLimpio_aunqueLaCadenaExplote() {
        FilterChain cadenaQueFalla = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class,
                () -> filtro.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), cadenaQueFalla));

        assertNull(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY),
                "Una excepcion no deberia dejar el traceId pegado al hilo");
    }

    @Test
    @DisplayName("noBorraOtrasClavesDelMdc_puestasPorTerceros")
    void noBorraOtrasClavesDelMdc_puestasPorTerceros() throws Exception {
        MDC.put("otraClave", "valor-de-otro-componente");

        filtro.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {});

        assertEquals("valor-de-otro-componente", MDC.get("otraClave"),
                "MDC.clear() borraria esto; el filtro debe sacar solo su propia clave");
    }
}
