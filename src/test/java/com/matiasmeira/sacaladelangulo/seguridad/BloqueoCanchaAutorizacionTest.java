package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractConfigOperativaSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bloqueos de cancha: POST / GET / DELETE /api/v1/establecimientos/{e}/canchas/{c}/bloqueos[/{id}].
 * @PreAuthorize OWNER/ADMIN en los tres (BloqueoCanchaController:26, :38, :49): el empleado queda afuera
 * aunque tenga todos los permisos. BloqueoCanchaService autoriza con validarPropietarioOAdmin (:48, :154,
 * :163). Sólo se testean casos con ids coherentes (cancha y bloqueo del complejo del path): los cruces de
 * path validan antes de autorizar (pendiente 69, abierto) y no se congelan acá. El GET por fecha
 * (/establecimientos/{e}/bloqueos) está en BloqueoMotivoAutorizacionTest.
 */
@DisplayName("Bloqueos de cancha /api/v1/establecimientos/{e}/canchas/{c}/bloqueos")
class BloqueoCanchaAutorizacionTest extends AbstractConfigOperativaSecurityTest {

    private String base(Long establecimientoId, Long canchaId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/canchas/" + canchaId + "/bloqueos";
    }

    private ResultActions crear(Usuario quien) throws Exception {
        LocalDate dia = LocalDate.now().plusDays(3);
        return mockMvc.perform(post(base(establecimientoA.getId(), canchaA.getId()))
                .header("Authorization", bearer(quien))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fechaInicio\":\"" + dia.atTime(15, 0) + "\",\"fechaFin\":\"" + dia.atTime(16, 0)
                        + "\",\"motivo\":\"Riego\"}"));
    }

    private ResultActions eliminar(Usuario quien) throws Exception {
        return mockMvc.perform(delete(base(establecimientoA.getId(), canchaA.getId()) + "/" + bloqueoA.getId())
                .header("Authorization", bearer(quien)));
    }

    private ResultActions listar(Long establecimientoId, Long canchaId, Usuario quien) throws Exception {
        return mockMvc.perform(get(base(establecimientoId, canchaId)).header("Authorization", bearer(quien)));
    }

    private void assertSinCambios() {
        assertEquals(1, bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaA.getId()).size());
        assertEquals(1, bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaB.getId()).size());
    }

    private Usuario empleadoTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    // ---- POST ----

    @Test
    @DisplayName("crear_duenoPropio_Devuelve201YPersiste")
    void crear_duenoPropio_Devuelve201YPersiste() throws Exception {
        crear(duenoA).andExpect(status().isCreated()).andExpect(jsonPath("$.canchaId").value(canchaA.getId()));
        assertEquals(2, bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaA.getId()).size());
    }

    @Test
    @DisplayName("crear_admin_Devuelve201")
    void crear_admin_Devuelve201() throws Exception {
        crear(admin).andExpect(status().isCreated());
        assertEquals(2, bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaA.getId()).size());
    }

    @Test
    @DisplayName("crear_duenoAjeno_Devuelve403SinPersistir")
    void crear_duenoAjeno_Devuelve403SinPersistir() throws Exception {
        crear(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_jugador_Devuelve403SinPersistir")
    void crear_jugador_Devuelve403SinPersistir() throws Exception {
        crear(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_empleadoConPermiso_Devuelve403SinPersistir")
    void crear_empleadoConPermiso_Devuelve403SinPersistir() throws Exception {
        crear(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_empleadoConTodosLosPermisos_Devuelve403SinPersistir")
    void crear_empleadoConTodosLosPermisos_Devuelve403SinPersistir() throws Exception {
        crear(empleadoTodosLosPermisos()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    // ---- DELETE ----

    @Test
    @DisplayName("eliminar_duenoPropio_Devuelve204YBorra")
    void eliminar_duenoPropio_Devuelve204YBorra() throws Exception {
        eliminar(duenoA).andExpect(status().isNoContent());
        assertTrue(bloqueoCanchaRepository.findById(bloqueoA.getId()).isEmpty());
        assertTrue(bloqueoCanchaRepository.findById(bloqueoB.getId()).isPresent());
    }

    @Test
    @DisplayName("eliminar_admin_Devuelve204YBorra")
    void eliminar_admin_Devuelve204YBorra() throws Exception {
        eliminar(admin).andExpect(status().isNoContent());
        assertTrue(bloqueoCanchaRepository.findById(bloqueoA.getId()).isEmpty());
    }

    @Test
    @DisplayName("eliminar_duenoAjeno_Devuelve403SinBorrar")
    void eliminar_duenoAjeno_Devuelve403SinBorrar() throws Exception {
        eliminar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_jugador_Devuelve403SinBorrar")
    void eliminar_jugador_Devuelve403SinBorrar() throws Exception {
        eliminar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_empleadoConPermiso_Devuelve403SinBorrar")
    void eliminar_empleadoConPermiso_Devuelve403SinBorrar() throws Exception {
        eliminar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_empleadoConTodosLosPermisos_Devuelve403SinBorrar")
    void eliminar_empleadoConTodosLosPermisos_Devuelve403SinBorrar() throws Exception {
        eliminar(empleadoTodosLosPermisos()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    // ---- GET por cancha ----

    @Test
    @DisplayName("listar_duenoPropio_Devuelve200ConSusBloqueos")
    void listar_duenoPropio_Devuelve200ConSusBloqueos() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bloqueoA.getId()));
    }

    @Test
    @DisplayName("listar_admin_Devuelve200")
    void listar_admin_Devuelve200() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(bloqueoA.getId()));
    }

    @Test
    @DisplayName("listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios")
    void listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios() throws Exception {
        listar(establecimientoB.getId(), canchaB.getId(), duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(bloqueoB.getId()))
                .andExpect(jsonPath("$[0].canchaId").value(canchaB.getId()));
    }

    @Test
    @DisplayName("listar_duenoAjeno_Devuelve403")
    void listar_duenoAjeno_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("listar_jugador_Devuelve403")
    void listar_jugador_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("listar_empleadoConPermiso_Devuelve403")
    void listar_empleadoConPermiso_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("listar_empleadoConTodosLosPermisos_Devuelve403")
    void listar_empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), canchaA.getId(), empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
