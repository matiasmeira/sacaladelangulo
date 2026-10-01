package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Base de los tests de autorización HTTP de feedback. Capa sobre {@link AbstractReservaSecurityTest}
 * (reusa su contexto único, {@code reserva(...)}, {@code canchaB}, {@code MENSAJE_403_ACCION}):
 * suma el repositorio de feedback, un helper para sembrar feedbacks y los mensajes de error del service.
 */
public abstract class AbstractFeedbackSecurityTest extends AbstractReservaSecurityTest {

    /** Cuerpo del 403 de FeedbackService.validarEsElJugador (FeedbackService:219). */
    protected static final String MENSAJE_403_JUGADOR = "No está autorizado para operar sobre el feedback de esta reserva";

    protected static final String BODY_FEEDBACK = "{\"puntuacion\":4,\"comentario\":\"Muy buena cancha\"}";

    @Autowired
    protected FeedbackRepository feedbackRepository;

    /** Feedback sembrado por repositorio sobre la reserva dada (puntuación 3, comentario "Original"). */
    protected Feedback feedback(Reserva reserva, boolean destacado) {
        return feedbackRepository.save(Feedback.builder()
                .reserva(reserva)
                .puntuacion(3)
                .comentario("Original")
                .destacado(destacado)
                .build());
    }

    protected Feedback recargar(Feedback feedback) {
        return feedbackRepository.findById(feedback.getId()).orElseThrow();
    }
}
