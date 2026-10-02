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
 * aunque tenga todos los permisos. BloqueoCanchaService autoriza primero contra el establecimiento del path
 * (pendiente 69): un establecimiento inexistente responde igual que uno ajeno (403), la cancha y el bloqueo se
 * buscan acotados a ese establecimiento (inexistente o de otro complejo: el mismo 404) y el body se valida
 * después de autorizar. El GET por fecha (/establecimientos/{e}/bloqueos) está en BloqueoMotivoAutorizacionTest.
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

    private static final long ID_INEXISTENTE = 987654321L;

    private ResultActions crearEn(Long establecimientoId, Long canchaId, String cuerpo, Usuario quien) throws Exception {
        return mockMvc.perform(post(base(establecimientoId, canchaId))
                .header("Authorization", bearer(quien))
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo));
    }

    private String cuerpoValido() {
        LocalDate dia = LocalDate.now().plusDays(3);
        return "{\"fechaInicio\":\"" + dia.atTime(15, 0) + "\",\"fechaFin\":\"" + dia.atTime(16, 0)
                + "\",\"motivo\":\"Riego\"}";
    }

    /** Fecha de inicio posterior a la de fin: el service lo rechaza con 400, pero sólo a quien está autorizado. */
    private String cuerpoConRangoInvertido() {
        LocalDate dia = LocalDate.now().plusDays(3);
        return "{\"fechaInicio\":\"" + dia.atTime(16, 0) + "\",\"fechaFin\":\"" + dia.atTime(15, 0)
                + "\",\"motivo\":\"Riego\"}";
    }

    private void assertCanchaNoEncontrada(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Cancha no encontrada"));
    }

    private void assertBloqueoNoEncontrado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Bloqueo no encontrado"));
    }

    // ---- sin oráculo de existencia (pendiente 69) ----

    @Test
    @DisplayName("crear_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void crear_establecimientoInexistente_Devuelve403IgualAlAjeno() throws Exception {
        crearEn(ID_INEXISTENTE, canchaA.getId(), cuerpoValido(), duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_establecimientoInexistenteComoAdmin_Devuelve404")
    void crear_establecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        crearEn(ID_INEXISTENTE, canchaA.getId(), cuerpoValido(), admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @DisplayName("crear_bodyInvalidoDeDuenoAjeno_Devuelve403No400")
    void crear_bodyInvalidoDeDuenoAjeno_Devuelve403No400() throws Exception {
        crearEn(establecimientoA.getId(), canchaA.getId(), cuerpoConRangoInvertido(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_bodyInvalidoDeDuenoPropio_Devuelve400")
    void crear_bodyInvalidoDeDuenoPropio_Devuelve400() throws Exception {
        crearEn(establecimientoA.getId(), canchaA.getId(), cuerpoConRangoInvertido(), duenoA)
                .andExpect(status().isBadRequest());
        assertSinCambios();
    }

    @Test
    @DisplayName("crear_canchaDeOtroComplejoPorElPathPropio_Devuelve404IgualAlInexistente")
    void crear_canchaDeOtroComplejoPorElPathPropio_Devuelve404() throws Exception {
        assertCanchaNoEncontrada(crearEn(establecimientoA.getId(), canchaB.getId(), cuerpoValido(), duenoA));
        assertCanchaNoEncontrada(crearEn(establecimientoA.getId(), ID_INEXISTENTE, cuerpoValido(), duenoA));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void eliminar_establecimientoInexistente_Devuelve403() throws Exception {
        mockMvc.perform(delete(base(ID_INEXISTENTE, canchaA.getId()) + "/" + bloqueoA.getId())
                        .header("Authorization", bearer(duenoA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_bloqueoDeOtroComplejoPorElPathPropio_Devuelve404IgualAlInexistenteSinBorrar")
    void eliminar_bloqueoDeOtroComplejoPorElPathPropio_Devuelve404() throws Exception {
        String propio = base(establecimientoA.getId(), canchaA.getId());
        assertBloqueoNoEncontrado(mockMvc.perform(delete(propio + "/" + bloqueoB.getId())
                .header("Authorization", bearer(duenoA))));
        assertBloqueoNoEncontrado(mockMvc.perform(delete(base(establecimientoA.getId(), canchaB.getId())
                + "/" + bloqueoB.getId()).header("Authorization", bearer(duenoA))));
        assertBloqueoNoEncontrado(mockMvc.perform(delete(propio + "/" + ID_INEXISTENTE)
                .header("Authorization", bearer(duenoA))));
        assertSinCambios();
    }

    @Test
    @DisplayName("listar_establecimientoInexistente_Devuelve403IgualAlAjeno")
    void listar_establecimientoInexistente_Devuelve403() throws Exception {
        listar(ID_INEXISTENTE, canchaA.getId(), duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("listar_canchaDeOtroComplejoPorElPathPropio_Devuelve404IgualAlInexistente")
    void listar_canchaDeOtroComplejoPorElPathPropio_Devuelve404() throws Exception {
        assertCanchaNoEncontrada(listar(establecimientoA.getId(), canchaB.getId(), duenoA));
        assertCanchaNoEncontrada(listar(establecimientoA.getId(), ID_INEXISTENTE, duenoA));
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
