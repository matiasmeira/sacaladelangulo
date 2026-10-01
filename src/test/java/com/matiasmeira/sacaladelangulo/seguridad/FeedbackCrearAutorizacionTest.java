package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractFeedbackSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/reservas/{id}/feedback. @PreAuthorize sólo PLAYER (FeedbackController:35). El service busca la
 * reserva (FeedbackService:58, 404 "Reserva no encontrada") y exige que sea el jugador de la reserva
 * (validarEsElJugador, FeedbackService:61 / 215-219, 403 con mensaje propio) ANTES de mirar el estado, así que un
 * jugador ajeno no se entera si la reserva está o no finalizada. Las reglas de negocio (estado FINALIZADA, una por
 * reserva, puntuación) tienen su test en FeedbackServiceTest y no se repiten.
 */
@DisplayName("POST /api/v1/reservas/{id}/feedback")
class FeedbackCrearAutorizacionTest extends AbstractFeedbackSecurityTest {

    private Reserva finalizada;

    @BeforeEach
    void sembrarReserva() {
        finalizada = reserva(canchaA, jugador, EstadoReserva.FINALIZADA);
    }

    private ResultActions crear(Long reservaId, Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/reservas/" + reservaId + "/feedback")
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY_FEEDBACK));
    }

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"duenoA", "duenoB", "admin", "empleadoConOperarCaja", "empleadoSinPermiso"})
    @DisplayName("rolDistintoDeJugador_Devuelve403PorAnotacionSinCrear")
    void rolDistintoDeJugador_Devuelve403PorAnotacionSinCrear(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "duenoA" -> duenoA;
            case "duenoB" -> duenoB;
            case "admin" -> admin;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            default -> empleadoSinPermiso;
        };

        crear(finalizada.getId(), usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    @DisplayName("otroJugadorSobreReservaAjena_Devuelve403DelServiceSinCrear")
    void otroJugadorSobreReservaAjena_Devuelve403DelServiceSinCrear() throws Exception {
        // FeedbackService:61 -> 219
        crear(finalizada.getId(), jugadorExtra()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_JUGADOR));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    @DisplayName("otroJugadorSobreReservaAjenaNoFinalizada_Devuelve403SinRevelarElEstado")
    void otroJugadorSobreReservaAjenaNoFinalizada_Devuelve403SinRevelarElEstado() throws Exception {
        // la pertenencia se valida antes que el estado (FeedbackService:61 antes de :63)
        Reserva confirmada = reserva(canchaA, jugador, EstadoReserva.CONFIRMADA, maniana().atTime(12, 0), 60);

        crear(confirmada.getId(), jugadorExtra()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_JUGADOR));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    @DisplayName("jugadorSobreReservaManualSinJugador_Devuelve403DelService")
    void jugadorSobreReservaManualSinJugador_Devuelve403DelService() throws Exception {
        // FeedbackService:216: reserva.getJugador() == null nunca coincide con nadie
        Reserva manual = reserva(canchaA, null, EstadoReserva.FINALIZADA, maniana().atTime(14, 0), 60);

        crear(manual.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_JUGADOR));
        assertEquals(0, feedbackRepository.count());
    }

    @Test
    @DisplayName("jugadorConReservaInexistente_Devuelve404")
    void jugadorConReservaInexistente_Devuelve404() throws Exception {
        // FeedbackService:58
        crear(999_999L, jugador).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Reserva no encontrada"));
    }

    @Test
    @DisplayName("autorSobreSuReservaFinalizada_Devuelve201YCreaElFeedback")
    void autorSobreSuReservaFinalizada_Devuelve201YCreaElFeedback() throws Exception {
        crear(finalizada.getId(), jugador).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservaId").value(finalizada.getId()))
                .andExpect(jsonPath("$.jugadorId").value(jugador.getId()))
                .andExpect(jsonPath("$.puntuacion").value(4))
                .andExpect(jsonPath("$.destacado").value(false));

        assertEquals(1, feedbackRepository.count());
        Feedback guardado = feedbackRepository.findAll().get(0);
        assertEquals(4, guardado.getPuntuacion());
        assertEquals("Muy buena cancha", guardado.getComentario());
        assertEquals(false, guardado.getDestacado());
    }
}
