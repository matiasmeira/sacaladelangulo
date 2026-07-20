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

        /**
         * Se persiste tal cual, sin sanitizar en el servidor (decisión consciente, ver B11
         * en la auditoría): el frontend actual es React, que escapa el texto por defecto al
         * renderizarlo (no usa dangerouslySetInnerHTML), así que hoy no hay XSS explotable.
         * Si en el futuro se agrega otro consumidor de esta API (otro frontend, una app
         * nativa, etc.), ese consumidor es responsable de hacer su propio output-encoding
         * antes de renderizar este campo, en particular donde se expone públicamente
         * ("destacado" en el perfil del establecimiento).
         */
        @Size(max = 1000, message = "El comentario no puede superar los 1000 caracteres")
        String comentario
) {
}
