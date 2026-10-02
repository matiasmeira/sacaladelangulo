package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractConfigOperativaSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloqueos de jugador, GET y DELETE /api/v1/establecimientos/{e}/jugadores-bloqueados[/{jugadorId}].
 * (El POST está en BloqueoJugadorAutorizacionTest.) @PreAuthorize OWNER/ADMIN (BloqueoJugadorController:
 * :40, :50); BloqueoJugadorService autoriza con validarPropietarioOAdmin contra el establecimiento del path
 * (pendiente 69) ANTES de buscar nada: un establecimiento inexistente responde igual que uno ajeno (403) y el
 * cruce (jugadorId bloqueado en otro complejo vía path propio) responde el 404 de "no está bloqueado en
 * este establecimiento" sin tocar nada.
 */
@DisplayName("Bloqueo de jugadores GET/DELETE /api/v1/establecimientos/{e}/jugadores-bloqueados")
class BloqueoJugadorGestionAutorizacionTest extends AbstractConfigOperativaSecurityTest {

    private static final String MENSAJE_NO_BLOQUEADO = "Este jugador no está bloqueado en este establecimiento";

    private String base(Long establecimientoId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/jugadores-bloqueados";
    }

    private ResultActions eliminar(Long establecimientoId, Long jugadorId, Usuario quien) throws Exception {
        return mockMvc.perform(delete(base(establecimientoId) + "/" + jugadorId).header("Authorization", bearer(quien)));
    }

    private ResultActions listar(Long establecimientoId, Usuario quien) throws Exception {
        return mockMvc.perform(get(base(establecimientoId)).header("Authorization", bearer(quien)));
    }

    private boolean bloqueadoEnA() {
        return bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimientoA.getId(), jugadorBloqueadoA.getId());
    }

    private boolean bloqueadoEnB() {
        return bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimientoB.getId(), jugadorBloqueadoB.getId());
    }

    private Usuario empleadoTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    private static final long ID_INEXISTENTE = 987654321L;

    @Test
    @DisplayName("eliminar_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void eliminar_establecimientoInexistente_Devuelve403() throws Exception {
        eliminar(ID_INEXISTENTE, jugadorBloqueadoA.getId(), duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertTrue(bloqueadoEnA());
        assertTrue(bloqueadoEnB());
    }

    @Test
    @DisplayName("eliminar_establecimientoInexistenteComoAdmin_Devuelve404")
    void eliminar_establecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        eliminar(ID_INEXISTENTE, jugadorBloqueadoA.getId(), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @DisplayName("listar_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void listar_establecimientoInexistente_Devuelve403() throws Exception {
        listar(ID_INEXISTENTE, duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    // ---- DELETE ----

    @Test
    @DisplayName("eliminar_duenoPropio_Devuelve204YDesbloquea")
    void eliminar_duenoPropio_Devuelve204YDesbloquea() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), duenoA).andExpect(status().isNoContent());
        assertFalse(bloqueadoEnA());
        assertTrue(bloqueadoEnB());
    }

    @Test
    @DisplayName("eliminar_admin_Devuelve204YDesbloquea")
    void eliminar_admin_Devuelve204YDesbloquea() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), admin).andExpect(status().isNoContent());
        assertFalse(bloqueadoEnA());
    }

    @Test
    @DisplayName("eliminar_duenoAjeno_Devuelve403SinDesbloquear")
    void eliminar_duenoAjeno_Devuelve403SinDesbloquear() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertTrue(bloqueadoEnA());
        assertTrue(bloqueadoEnB());
    }

    @Test
    @DisplayName("eliminar_duenoDeOtroComplejoConPathPropioYJugadorBloqueadoEnElAjeno_Devuelve404SinDesbloquear")
    void eliminar_duenoDeOtroComplejoConPathPropioYJugadorAjeno_Devuelve404() throws Exception {
        // Cruce: path de B (autoriza) con el jugador bloqueado en A -> BloqueoJugadorService:79 (no hay bloqueo en B)
        eliminar(establecimientoB.getId(), jugadorBloqueadoA.getId(), duenoB).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(MENSAJE_NO_BLOQUEADO));
        assertTrue(bloqueadoEnA());
        assertTrue(bloqueadoEnB());
    }

    @Test
    @DisplayName("eliminar_jugador_Devuelve403SinDesbloquear")
    void eliminar_jugador_Devuelve403SinDesbloquear() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertTrue(bloqueadoEnA());
    }

    @Test
    @DisplayName("eliminar_empleadoConPermiso_Devuelve403SinDesbloquear")
    void eliminar_empleadoConPermiso_Devuelve403SinDesbloquear() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertTrue(bloqueadoEnA());
    }

    @Test
    @DisplayName("eliminar_empleadoConTodosLosPermisos_Devuelve403SinDesbloquear")
    void eliminar_empleadoConTodosLosPermisos_Devuelve403SinDesbloquear() throws Exception {
        eliminar(establecimientoA.getId(), jugadorBloqueadoA.getId(), empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertTrue(bloqueadoEnA());
    }

    // ---- GET ----

    @Test
    @DisplayName("listar_duenoPropio_Devuelve200SoloConLosPropios")
    void listar_duenoPropio_Devuelve200SoloConLosPropios() throws Exception {
        listar(establecimientoA.getId(), duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jugadorId").value(jugadorBloqueadoA.getId()));
    }

    @Test
    @DisplayName("listar_admin_Devuelve200")
    void listar_admin_Devuelve200() throws Exception {
        listar(establecimientoA.getId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].jugadorId").value(jugadorBloqueadoA.getId()));
    }

    @Test
    @DisplayName("listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios")
    void listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios() throws Exception {
        listar(establecimientoB.getId(), duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].jugadorId").value(jugadorBloqueadoB.getId()));
    }

    @Test
    @DisplayName("listar_duenoAjeno_Devuelve403")
    void listar_duenoAjeno_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("listar_jugador_Devuelve403")
    void listar_jugador_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("listar_empleadoConPermiso_Devuelve403")
    void listar_empleadoConPermiso_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("listar_empleadoConTodosLosPermisos_Devuelve403")
    void listar_empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
