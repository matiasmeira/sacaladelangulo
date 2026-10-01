package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.support.AbstractFeedbackSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT y DELETE /api/v1/feedback/{id}. @PreAuthorize sólo PLAYER (FeedbackController:50 y :64). El service busca el
 * feedback (FeedbackService:110 y :135, 404 "Feedback no encontrado") y exige que el usuario sea el jugador de la
 * reserva del feedback (validarEsElJugador, :112 y :137 / 215-219, 403 con mensaje propio). Un jugador no puede
 * editar ni borrar el feedback de otro jugador.
 */
@DisplayName("PUT/DELETE /api/v1/feedback/{id}")
class FeedbackEditarEliminarAutorizacionTest extends AbstractFeedbackSecurityTest {

    private Feedback delJugador;
    private Feedback delOtroJugador;
    private Usuario otroJugador;

    @BeforeEach
    void sembrarFeedbacks() {
        otroJugador = jugadorExtra();
        delJugador = feedback(reserva(canchaA, jugador, EstadoReserva.FINALIZADA), false);
        delOtroJugador = feedback(reserva(canchaA, otroJugador, EstadoReserva.FINALIZADA,
                maniana().atTime(12, 0), 60), false);
    }

    private ResultActions editar(Long feedbackId, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/feedback/" + feedbackId)
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY_FEEDBACK));
    }

    private ResultActions eliminar(Long feedbackId, Usuario usuario) throws Exception {
        return mockMvc.perform(delete("/api/v1/feedback/" + feedbackId)
                .header("Authorization", bearer(usuario)));
    }

    private Usuario usuarioNoJugador(String quien) {
        return switch (quien) {
            case "duenoA" -> duenoA;
            case "duenoB" -> duenoB;
            case "admin" -> admin;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            default -> empleadoSinPermiso;
        };
    }

    private void assertSinCambios() {
        assertEquals(2, feedbackRepository.count());
        Feedback a = recargar(delJugador);
        assertEquals(3, a.getPuntuacion());
        assertEquals("Original", a.getComentario());
        Feedback b = recargar(delOtroJugador);
        assertEquals(3, b.getPuntuacion());
        assertEquals("Original", b.getComentario());
    }

    // ---- editar ----

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"duenoA", "duenoB", "admin", "empleadoConOperarCaja", "empleadoSinPermiso"})
    @DisplayName("editar_rolDistintoDeJugador_Devuelve403PorAnotacionSinCambios")
    void editar_rolDistintoDeJugador_Devuelve403PorAnotacionSinCambios(String quien) throws Exception {
        editar(delJugador.getId(), usuarioNoJugador(quien)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("editar_otroJugador_Devuelve403DelServiceSinCambios")
    void editar_otroJugador_Devuelve403DelServiceSinCambios() throws Exception {
        // FeedbackService:112 -> 219
        editar(delJugador.getId(), otroJugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_JUGADOR));
        assertSinCambios();
    }

    @Test
    @DisplayName("editar_inexistente_Devuelve404")
    void editar_inexistente_Devuelve404() throws Exception {
        // FeedbackService:110
        editar(999_999L, jugador).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feedback no encontrado"));
    }

    @Test
    @DisplayName("editar_autor_Devuelve200YActualizaSoloElSuyo")
    void editar_autor_Devuelve200YActualizaSoloElSuyo() throws Exception {
        editar(delJugador.getId(), jugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(delJugador.getId()))
                .andExpect(jsonPath("$.puntuacion").value(4))
                .andExpect(jsonPath("$.comentario").value("Muy buena cancha"));

        Feedback editado = recargar(delJugador);
        assertEquals(4, editado.getPuntuacion());
        assertEquals("Muy buena cancha", editado.getComentario());
        Feedback ajeno = recargar(delOtroJugador);
        assertEquals(3, ajeno.getPuntuacion());
        assertEquals("Original", ajeno.getComentario());
    }

    // ---- eliminar ----

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"duenoA", "duenoB", "admin", "empleadoConOperarCaja", "empleadoSinPermiso"})
    @DisplayName("eliminar_rolDistintoDeJugador_Devuelve403PorAnotacionSinCambios")
    void eliminar_rolDistintoDeJugador_Devuelve403PorAnotacionSinCambios(String quien) throws Exception {
        eliminar(delJugador.getId(), usuarioNoJugador(quien)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_otroJugador_Devuelve403DelServiceSinCambios")
    void eliminar_otroJugador_Devuelve403DelServiceSinCambios() throws Exception {
        // FeedbackService:137 -> 219
        eliminar(delJugador.getId(), otroJugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_JUGADOR));
        assertSinCambios();
    }

    @Test
    @DisplayName("eliminar_inexistente_Devuelve404")
    void eliminar_inexistente_Devuelve404() throws Exception {
        // FeedbackService:135
        eliminar(999_999L, jugador).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Feedback no encontrado"));
    }

    @Test
    @DisplayName("eliminar_autor_Devuelve204YBorraSoloElSuyo")
    void eliminar_autor_Devuelve204YBorraSoloElSuyo() throws Exception {
        eliminar(delJugador.getId(), jugador).andExpect(status().isNoContent());

        assertTrue(feedbackRepository.findById(delJugador.getId()).isEmpty());
        assertTrue(feedbackRepository.findById(delOtroJugador.getId()).isPresent());
        assertEquals(1, feedbackRepository.count());
    }
}
