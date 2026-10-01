package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.support.AbstractFeedbackSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/feedback/{id}/destacar y /quitar-destacado. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (FeedbackController:94 y :107). El service busca el feedback (FeedbackService:235, 404 "Feedback no encontrado"),
 * toma el establecimiento de la reserva del feedback y exige validarAccion(FIJAR_COMENTARIO_DESTACADO) sobre ESE
 * establecimiento (:167 y :193; AutorizacionEmpleadoService:59, 403 con el mensaje de acción): el dueño o el
 * empleado de B no pueden tocar un comentario del complejo A.
 */
@DisplayName("PUT /api/v1/feedback/{id}/destacar y quitar-destacado")
class FeedbackDestacarAutorizacionTest extends AbstractFeedbackSecurityTest {

    private Feedback feedbackA;
    private Feedback feedbackB;
    private Usuario empleadoConFijar;

    @BeforeEach
    void sembrarFeedbacks() {
        feedbackA = feedback(reserva(canchaA, jugador, EstadoReserva.FINALIZADA), false);
        feedbackB = feedback(reserva(canchaB, jugadorExtra(), EstadoReserva.FINALIZADA), false);
        empleadoConFijar = empleado(establecimientoA, Set.of(PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO));
    }

    private ResultActions destacar(Long feedbackId, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/feedback/" + feedbackId + "/destacar")
                .header("Authorization", bearer(usuario)));
    }

    private ResultActions quitar(Long feedbackId, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/feedback/" + feedbackId + "/quitar-destacado")
                .header("Authorization", bearer(usuario)));
    }

    private Usuario sinAcceso(String quien) {
        return switch (quien) {
            case "empleadoSinPermiso" -> empleadoSinPermiso;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            case "empleadoDeB" -> empleado(establecimientoB, Set.of(PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO));
            default -> duenoB;
        };
    }

    private Usuario conAcceso(String quien) {
        return switch (quien) {
            case "empleadoConFijar" -> empleadoConFijar;
            case "duenoA" -> duenoA;
            default -> admin;
        };
    }

    private void marcarDestacado(Feedback feedback) {
        feedback.setDestacado(true);
        feedbackRepository.save(feedback);
    }

    // ---- destacar ----

    @Test
    @DisplayName("destacar_jugador_Devuelve403PorAnotacionSinCambios")
    void destacar_jugador_Devuelve403PorAnotacionSinCambios() throws Exception {
        destacar(feedbackA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(false, recargar(feedbackA).getDestacado());
    }

    @ParameterizedTest(name = "{0}_Devuelve403DelService")
    @ValueSource(strings = {"empleadoSinPermiso", "empleadoConOperarCaja", "empleadoDeB", "duenoB"})
    @DisplayName("destacar_sinAcceso_Devuelve403DelServiceSinCambios")
    void destacar_sinAcceso_Devuelve403DelServiceSinCambios(String quien) throws Exception {
        // FeedbackService:167 -> AutorizacionEmpleadoService:59
        destacar(feedbackA.getId(), sinAcceso(quien)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        assertEquals(false, recargar(feedbackA).getDestacado());
        assertEquals(false, recargar(feedbackB).getDestacado());
    }

    @Test
    @DisplayName("destacar_inexistente_Devuelve404")
    void destacar_inexistente_Devuelve404() throws Exception {
        // FeedbackService:235
        destacar(999_999L, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feedback no encontrado"));
    }

    @ParameterizedTest(name = "{0}_Devuelve200YDestaca")
    @ValueSource(strings = {"empleadoConFijar", "duenoA", "admin"})
    @DisplayName("destacar_conAcceso_Devuelve200YDestacaSoloElDeA")
    void destacar_conAcceso_Devuelve200YDestacaSoloElDeA(String quien) throws Exception {
        destacar(feedbackA.getId(), conAcceso(quien)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(feedbackA.getId()))
                .andExpect(jsonPath("$.destacado").value(true));
        assertEquals(true, recargar(feedbackA).getDestacado());
        assertEquals(false, recargar(feedbackB).getDestacado());
    }

    // ---- quitar destacado ----

    @Test
    @DisplayName("quitar_jugador_Devuelve403PorAnotacionSinCambios")
    void quitar_jugador_Devuelve403PorAnotacionSinCambios() throws Exception {
        marcarDestacado(feedbackA);

        quitar(feedbackA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(true, recargar(feedbackA).getDestacado());
    }

    @ParameterizedTest(name = "{0}_Devuelve403DelService")
    @ValueSource(strings = {"empleadoSinPermiso", "empleadoConOperarCaja", "empleadoDeB", "duenoB"})
    @DisplayName("quitar_sinAcceso_Devuelve403DelServiceSinCambios")
    void quitar_sinAcceso_Devuelve403DelServiceSinCambios(String quien) throws Exception {
        marcarDestacado(feedbackA);

        // FeedbackService:193 -> AutorizacionEmpleadoService:59
        quitar(feedbackA.getId(), sinAcceso(quien)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        assertEquals(true, recargar(feedbackA).getDestacado());
    }

    @Test
    @DisplayName("quitar_inexistente_Devuelve404")
    void quitar_inexistente_Devuelve404() throws Exception {
        quitar(999_999L, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feedback no encontrado"));
    }

    @ParameterizedTest(name = "{0}_Devuelve200YQuita")
    @ValueSource(strings = {"empleadoConFijar", "duenoA", "admin"})
    @DisplayName("quitar_conAcceso_Devuelve200YQuitaSoloElDeA")
    void quitar_conAcceso_Devuelve200YQuitaSoloElDeA(String quien) throws Exception {
        marcarDestacado(feedbackA);
        marcarDestacado(feedbackB);

        quitar(feedbackA.getId(), conAcceso(quien)).andExpect(status().isOk())
                .andExpect(jsonPath("$.destacado").value(false));
        assertEquals(false, recargar(feedbackA).getDestacado());
        assertEquals(true, recargar(feedbackB).getDestacado());
    }
}
