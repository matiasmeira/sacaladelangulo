package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/gastos. @PreAuthorize OWNER/ADMIN (GastoController:42); el
 * dueño ajeno lo rechaza AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135),
 * invocado desde GastoService.registrarGasto (línea 53), como AccessDeniedException -> 403.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/gastos")
class GastoAutorizacionTest extends AbstractSecurityWebTest {

    @Autowired
    private GastoRepository gastoRepository;

    private ResultActions registrar(String authorization) throws Exception {
        var req = post("/api/v1/establecimientos/" + establecimientoA.getId() + "/gastos")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fecha\":\"" + LocalDate.now() + "\",\"monto\":500,\"categoria\":\"INSUMOS\","
                        + "\"descripcion\":\"Pelotas\",\"metodoPago\":\"TRANSFERENCIA\"}");
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        return mockMvc.perform(req);
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
    void establecimientoInexistente_Devuelve403IgualAlAjeno() throws Exception {
        mockMvc.perform(post("/api/v1/establecimientos/987654321/gastos")
                        .header("Authorization", bearer(duenoA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fecha\":\"" + LocalDate.now() + "\",\"monto\":500,\"categoria\":\"INSUMOS\","
                                + "\"descripcion\":\"Pelotas\",\"metodoPago\":\"TRANSFERENCIA\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
        assertEquals(0, gastoRepository.count());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        registrar(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertEquals(0, gastoRepository.count());
    }

    @Test
    @DisplayName("empleado_Devuelve403")
    void empleado_Devuelve403() throws Exception {
        registrar(bearer(empleadoConPermiso)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(0, gastoRepository.count());
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        registrar(bearer(duenoB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
        assertEquals(0, gastoRepository.count());
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201")
    void duenoDelEstablecimiento_Devuelve201() throws Exception {
        registrar(bearer(duenoA)).andExpect(status().isCreated());
        assertEquals(1, gastoRepository.count());
    }
}
