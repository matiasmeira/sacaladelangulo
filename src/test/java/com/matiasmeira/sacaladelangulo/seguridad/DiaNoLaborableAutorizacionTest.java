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
 * Días no laborables: POST / GET / DELETE /api/v1/establecimientos/{e}/dias-no-laborables[/{id}].
 * @PreAuthorize OWNER/ADMIN en los tres (DiaNoLaborableController:29, :39, :49): el empleado queda afuera
 * aunque tenga todos los permisos. DiaNoLaborableService autoriza con validarPropietarioOAdmin (:31, :59,
 * :68). El cruce "id de un día de otro complejo vía path propio" valida antes de autorizar en eliminar
 * (:52-:59, pendiente 69, abierto) y no se congela acá.
 */
@DisplayName("Días no laborables /api/v1/establecimientos/{e}/dias-no-laborables")
class DiaNoLaborableAutorizacionTest extends AbstractConfigOperativaSecurityTest {

    private String base(Long establecimientoId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/dias-no-laborables";
    }

    private ResultActions crear(Long establecimientoId, Usuario quien) throws Exception {
        return mockMvc.perform(post(base(establecimientoId))
                .header("Authorization", bearer(quien))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fecha\":\"" + LocalDate.now().plusDays(20) + "\",\"motivo\":\"Cierre\"}"));
    }

    private ResultActions eliminar(Long establecimientoId, Long id, Usuario quien) throws Exception {
        return mockMvc.perform(delete(base(establecimientoId) + "/" + id).header("Authorization", bearer(quien)));
    }

    private ResultActions listar(Long establecimientoId, Usuario quien) throws Exception {
        return mockMvc.perform(get(base(establecimientoId)).header("Authorization", bearer(quien)));
    }

    private void assertSinCambios() {
        assertEquals(1, diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoA.getId()).size());
        assertEquals(1, diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoB.getId()).size());
    }

    private Usuario empleadoTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    // ---- POST ----

    @Test
    @DisplayName("crear_duenoPropio_Devuelve201YPersiste")
    void crear_duenoPropio_Devuelve201YPersiste() throws Exception {
        crear(establecimientoA.getId(), duenoA).andExpect(status().isCreated());
        assertEquals(2, diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoA.getId()).size());
        assertEquals(1, diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoB.getId()).size());
    }

    @Test
    @DisplayName("crear_admin_Devuelve201")
    void crear_admin_Devuelve201() throws Exception {
        crear(establecimientoA.getId(), admin).andExpect(status().isCreated());
        assertEquals(2, diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoA.getId()).size());
    }

    @Test
    @DisplayName("crear_duenoAjeno_Devuelve403SinPersistir")
    void crear_duenoAjeno_Devuelve403SinPersistir() throws Exception {
        crear(establecimientoA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_jugador_Devuelve403SinPersistir")
    void crear_jugador_Devuelve403SinPersistir() throws Exception {
        crear(establecimientoA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_empleadoConPermiso_Devuelve403SinPersistir")
    void crear_empleadoConPermiso_Devuelve403SinPersistir() throws Exception {
        crear(establecimientoA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_empleadoConTodosLosPermisos_Devuelve403SinPersistir")
    void crear_empleadoConTodosLosPermisos_Devuelve403SinPersistir() throws Exception {
        crear(establecimientoA.getId(), empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    // ---- DELETE ----

    @Test
    @DisplayName("eliminar_duenoPropio_Devuelve204YBorra")
    void eliminar_duenoPropio_Devuelve204YBorra() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), duenoA).andExpect(status().isNoContent());
        assertTrue(diaNoLaborableRepository.findById(diaA.getId()).isEmpty());
        assertTrue(diaNoLaborableRepository.findById(diaB.getId()).isPresent());
    }

    @Test
    @DisplayName("eliminar_admin_Devuelve204YBorra")
    void eliminar_admin_Devuelve204YBorra() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), admin).andExpect(status().isNoContent());
        assertTrue(diaNoLaborableRepository.findById(diaA.getId()).isEmpty());
    }

    @Test
    @DisplayName("eliminar_duenoAjeno_Devuelve403SinBorrar")
    void eliminar_duenoAjeno_Devuelve403SinBorrar() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_jugador_Devuelve403SinBorrar")
    void eliminar_jugador_Devuelve403SinBorrar() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_empleadoConPermiso_Devuelve403SinBorrar")
    void eliminar_empleadoConPermiso_Devuelve403SinBorrar() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_empleadoConTodosLosPermisos_Devuelve403SinBorrar")
    void eliminar_empleadoConTodosLosPermisos_Devuelve403SinBorrar() throws Exception {
        eliminar(establecimientoA.getId(), diaA.getId(), empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    // ---- GET ----

    @Test
    @DisplayName("listar_duenoPropio_Devuelve200SoloConLosPropios")
    void listar_duenoPropio_Devuelve200SoloConLosPropios() throws Exception {
        listar(establecimientoA.getId(), duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(diaA.getId()));
    }

    @Test
    @DisplayName("listar_admin_Devuelve200")
    void listar_admin_Devuelve200() throws Exception {
        listar(establecimientoA.getId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(diaA.getId()));
    }

    @Test
    @DisplayName("listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios")
    void listar_duenoDeOtroComplejoEnSuPath_SoloVeLosPropios() throws Exception {
        listar(establecimientoB.getId(), duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(diaB.getId()));
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
