package com.matiasmeira.sacaladelangulo.feedback.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO para solicitud de calificación de una reserva finalizada.
 */
public record FeedbackRequest(
        @NotNull(message = "La puntuación es obligatoria")
        @Min(value = 1, message = "La puntuación mínima es 1")
        @Max(value = 5, message = "La puntuación máxima es 5")
        Integer puntuacion,

        @Size(max = 1000, message = "El comentario no puede superar los 1000 caracteres")
        String comentario
) {
}
