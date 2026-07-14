package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.LocalDateTime;

/**
 * Comentario destacado (fijado por el dueño) de un establecimiento.
 */
public record FeedbackDestacadoDto(
        Long feedbackId,
        Integer puntuacion,
        String comentario,
        String jugadorNombre,
        LocalDateTime fechaCreacion
) {
}
