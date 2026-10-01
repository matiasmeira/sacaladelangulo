package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}. @PreAuthorize OWNER/ADMIN
 * (CanchaController:65). CanchaService.actualizarCancha: busca el complejo (:136), valida con
 * validarPropietarioOAdmin (:137) -> 403 al dueño ajeno; después busca la cancha (:139, 404) y exige
 * que sea del complejo del path (:141-142, IllegalArgumentException -> 400 "La cancha no pertenece a
 * este establecimiento"). El orden importa: el cruce se evalúa DESPUÉS de autorizar sobre el path.
 */
@DisplayName("PUT /api/v1/establecimientos/{id}/canchas/{canchaId}")
class CanchaActualizarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String MENSAJE_CRUCE = "La cancha no pertenece a este establecimiento";
    private static final String BODY = "{\"nombre\":\"Cancha Editada\",\"deportes\":[\"PADEL\"],\"precioBase\":2500}";

    private Cancha canchaB;
    private String nombreCanchaA;
    private String nombreCanchaB;

    @BeforeEach
    void sembrarCanchaB() {
        canchaB = canchaRepository.save(Canchas.canchaActiva(establecimientoB));
        nombreCanchaA = canchaA.getNombre();
        nombreCanchaB = canchaB.getNombre();
    }

    private ResultActions actualizar(Long establecimientoId, Long canchaId, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/establecimientos/" + establecimientoId + "/canchas/" + canchaId)
                .header("Authorization", bearer(usuario))
                .contentType("application/json")
                .content(BODY));
    }

    private Cancha recargar(Cancha c) {
        return canchaRepository.findById(c.getId()).orElseThrow();
    }

    private void assertSinCambios() {
        assertEquals(nombreCanchaA, recargar(canchaA).getNombre());
        assertEquals(nombreCanchaB, recargar(canchaB).getNombre());
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(recargar(canchaA).getPrecioBase()));
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(recargar(canchaB).getPrecioBase()));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), canchaA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), canchaA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), canchaA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjeno_Devuelve403")
    void duenoDeOtroEstablecimientoConPathAjeno_Devuelve403() throws Exception {
        // CanchaService:137 -> AutorizacionEmpleadoService:135
        actualizar(establecimientoA.getId(), canchaA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjenoYCanchaPropia_Devuelve403")
    void duenoDeOtroEstablecimientoConPathAjenoYCanchaPropia_Devuelve403() throws Exception {
        // La autorización va primero (:137): no se llega a mirar a quién pertenece la cancha.
        actualizar(establecimientoA.getId(), canchaB.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathPropioYCanchaAjena_Devuelve400SinCambios")
    void duenoDeOtroEstablecimientoConPathPropioYCanchaAjena_Devuelve400SinCambios() throws Exception {
        // Cruce: path de B (autoriza) con la cancha de A -> CanchaService:141-142
        actualizar(establecimientoB.getId(), canchaA.getId(), duenoB)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_CRUCE));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDelEstablecimientoConCanchaDeOtroComplejo_Devuelve400SinCambios")
    void duenoDelEstablecimientoConCanchaDeOtroComplejo_Devuelve400SinCambios() throws Exception {
        // Mismo cruce a la inversa: path de A con la cancha de B
        actualizar(establecimientoA.getId(), canchaB.getId(), duenoA)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_CRUCE));
        assertSinCambios();
    }

    @Test
    @DisplayName("adminConCanchaDeOtroComplejo_Devuelve400SinCambios")
    void adminConCanchaDeOtroComplejo_Devuelve400SinCambios() throws Exception {
        // El ADMIN pasa la autorización pero el cruce también lo frena.
        actualizar(establecimientoA.getId(), canchaB.getId(), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_CRUCE));
        assertSinCambios();
    }

    @Test
    @DisplayName("canchaInexistente_Devuelve404")
    void canchaInexistente_Devuelve404() throws Exception {
        // CanchaService:554
        actualizar(establecimientoA.getId(), 999_999L, duenoA)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YActualizaSoloEsaCancha")
    void duenoDelEstablecimiento_Devuelve200YActualizaSoloEsaCancha() throws Exception {
        actualizar(establecimientoA.getId(), canchaA.getId(), duenoA)
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Cancha Editada"));
        assertEquals("Cancha Editada", recargar(canchaA).getNombre());
        assertEquals(0, BigDecimal.valueOf(2500).compareTo(recargar(canchaA).getPrecioBase()));
        assertEquals(nombreCanchaB, recargar(canchaB).getNombre());
    }

    @Test
    @DisplayName("admin_Devuelve200YActualizaLaCanchaDelComplejoAjeno")
    void admin_Devuelve200YActualizaLaCanchaDelComplejoAjeno() throws Exception {
        actualizar(establecimientoA.getId(), canchaA.getId(), admin).andExpect(status().isOk());
        assertEquals("Cancha Editada", recargar(canchaA).getNombre());
        assertEquals(nombreCanchaB, recargar(canchaB).getNombre());
    }
}
