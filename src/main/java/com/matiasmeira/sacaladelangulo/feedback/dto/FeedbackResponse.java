package com.matiasmeira.sacaladelangulo.feedback.dto;

import java.time.LocalDateTime;

/**
 * DTO de respuesta para un feedback de reserva.
 */
public record FeedbackResponse(
        Long id,
        Long reservaId,
        Long establecimientoId,
        Long jugadorId,
        String jugadorNombre,
        Integer puntuacion,
        String comentario,
        Boolean destacado,
        LocalDateTime fechaCreacion
) {
}
