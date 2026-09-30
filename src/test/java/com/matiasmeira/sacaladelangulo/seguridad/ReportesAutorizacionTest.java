package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/reportes/* (7 endpoints, ReporteController). Todos llevan
 * @PreAuthorize OWNER/ADMIN (ReporteController:54, 70, 87, 103, 120, 136, 151) y su service llama a
 * ReporteAutorizacionService.validarDuenoDelEstablecimiento (línea 27), que delega en
 * AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135): el dueño ajeno recibe
 * AccessDeniedException("No autorizado en este establecimiento") -> 403. Invocado desde
 * ReporteFacturacionService:39, ReporteOcupacionService:55, ReporteHorariosService:41,
 * ReporteClientesService:39, ReporteGastosService:42 (gastos) y :78 (resultado), ReporteCierreCajaService:35.
 * El 401 sin token lo cubre SinTokenBarridoTest.
 */
class ReportesAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_DUENO_AJENO = "No autorizado en este establecimiento";

    /** Un endpoint de reportes: ruta, dónde viene el rango pedido en la respuesta y un campo propio del reporte. */
    record Endpoint(String ruta, String pathDesde, String pathPropio) {
        @Override
        public String toString() {
            return ruta;
        }
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(
                new Endpoint("facturacion", "$.periodoActual.desde", "$.serieTemporal"),
                new Endpoint("ocupacion", "$.periodoActual.desde", "$.notaMetodologica"),
                new Endpoint("horarios-pedidos", "$.periodo.desde", "$.ranking"),
                new Endpoint("clientes", "$.periodoActual.desde", "$.topClientes"),
                new Endpoint("gastos", "$.periodoActual.desde", "$.desglosePorCategoria"),
                new Endpoint("resultado", "$.periodoActual.desde", "$.neto.actual"),
                new Endpoint("cierres-caja", "$.periodo.desde", "$.turnos")
        ).map(Arguments::of);
    }

    private final LocalDate hasta = LocalDate.now();
    private final LocalDate desde = hasta.minusDays(6);

    private ResultActions pedir(Endpoint endpoint, String authorization) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/reportes/" + endpoint.ruta())
                .param("desde", desde.toString())
                .param("hasta", hasta.toString())
                .header("Authorization", authorization));
    }

    private void assertOk(Endpoint endpoint, ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isOk())
                .andExpect(jsonPath(endpoint.pathDesde()).value(desde.toString()))
                .andExpect(jsonPath(endpoint.pathPropio()).exists());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void rol_jugador_Devuelve403(Endpoint endpoint) throws Exception {
        pedir(endpoint, bearer(jugador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void rol_empleado_Devuelve403(Endpoint endpoint) throws Exception {
        pedir(endpoint, bearer(empleadoConPermiso))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void duenoAjeno_Devuelve403(Endpoint endpoint) throws Exception {
        pedir(endpoint, bearer(duenoB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_DUENO_AJENO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void admin_Devuelve200(Endpoint endpoint) throws Exception {
        assertOk(endpoint, pedir(endpoint, bearer(admin)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void duenoPropio_Devuelve200(Endpoint endpoint) throws Exception {
        assertOk(endpoint, pedir(endpoint, bearer(duenoA)));
    }
}
