package com.matiasmeira.sacaladelangulo.feedback.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.stereotype.Component;

@Component
public class FeedbackMapper {

    public FeedbackResponse mapToResponse(Feedback feedback) {
        Reserva reserva = feedback.getReserva();
        Usuario jugador = reserva.getJugador();
        return new FeedbackResponse(
                feedback.getId(),
                reserva.getId(),
                reserva.getCancha().getEstablecimiento().getId(),
                jugador != null ? jugador.getId() : null,
                jugador != null ? jugador.getNombre() : null,
                feedback.getPuntuacion(),
                feedback.getComentario(),
                feedback.getDestacado(),
                feedback.getFechaCreacion()
        );
    }
}
