package com.matiasmeira.sacaladelangulo.reserva.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * DTO para solicitud de creación de reserva.
 */
public record ReservaRequest(
        @NotNull(message = "El ID de la cancha es obligatorio")
        Long canchaId,

        @NotNull(message = "La fecha y hora de inicio es obligatoria")
        LocalDateTime fechaHoraInicio,

        @NotNull(message = "La fecha y hora de fin es obligatoria")
        LocalDateTime fechaHoraFin
) {
}
