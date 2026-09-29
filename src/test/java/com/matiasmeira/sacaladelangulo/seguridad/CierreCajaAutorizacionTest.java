package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/caja/{turnoId}/cerrar. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (CierreCajaController:72); el dueño ajeno y el empleado sin permiso los rechaza
 * AutorizacionEmpleadoService.validarAccion (línea 59), invocado desde TurnoCajaService.cerrarCaja
 * (línea 232), como AccessDeniedException -> 403 por GlobalExceptionHandler.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/caja/{turnoId}/cerrar")
class CierreCajaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado para realizar esta acción en este establecimiento";

    @Autowired
    private TurnoCajaRepository turnoCajaRepository;

    private TurnoCaja turno;

    @BeforeEach
    void abrirTurno() {
        turno = turnoCajaRepository.save(TurnoCaja.builder()
                .establecimiento(establecimientoA)
                .usuarioApertura(duenoA)
                .fechaApertura(LocalDateTime.now().minusHours(1))
                .fondoInicial(new BigDecimal("1000"))
                .estado(EstadoTurnoCaja.ABIERTO)
                .build());
    }

    private ResultActions cerrar(String authorization) throws Exception {
        var req = post("/api/v1/establecimientos/" + establecimientoA.getId() + "/caja/" + turno.getId() + "/cerrar")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"saldoRealContado\":1000}");
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        return mockMvc.perform(req);
    }

    private void assertTurno(EstadoTurnoCaja esperado) {
        assertEquals(esperado, turnoCajaRepository.findById(turno.getId()).orElseThrow().getEstado());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        cerrar(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertTurno(EstadoTurnoCaja.ABIERTO);
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        cerrar(bearer(jugador)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertTurno(EstadoTurnoCaja.ABIERTO);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        cerrar(bearer(duenoB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertTurno(EstadoTurnoCaja.ABIERTO);
    }

    @Test
    @DisplayName("empleadoSinPermisoOperarCaja_Devuelve403")
    void empleadoSinPermisoOperarCaja_Devuelve403() throws Exception {
        cerrar(bearer(empleadoSinPermiso))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertTurno(EstadoTurnoCaja.ABIERTO);
    }

    @Test
    @DisplayName("empleadoConPermisoOperarCaja_Devuelve200")
    void empleadoConPermisoOperarCaja_Devuelve200() throws Exception {
        cerrar(bearer(empleadoConPermiso)).andExpect(status().isOk());
        assertTurno(EstadoTurnoCaja.CERRADO);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YCierraElTurno")
    void duenoDelEstablecimiento_Devuelve200YCierraElTurno() throws Exception {
        cerrar(bearer(duenoA)).andExpect(status().isOk());
        assertTurno(EstadoTurnoCaja.CERRADO);
    }
}
