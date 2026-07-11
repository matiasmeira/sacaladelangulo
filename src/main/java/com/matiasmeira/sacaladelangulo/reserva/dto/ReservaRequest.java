package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * DTO para solicitud de creación de reserva.
 */
public record ReservaRequest(
        @NotNull(message = "El ID de la cancha es obligatorio")
        Long canchaId,

        @NotNull(message = "La fecha y hora de inicio es obligatoria")
        @FutureOrPresent(message = "La fecha y hora de inicio debe ser en el presente o futuro")
        LocalDateTime fechaHoraInicio,

        @NotNull(message = "La fecha y hora de fin es obligatoria")
        @FutureOrPresent(message = "La fecha y hora de fin debe ser en el presente o futuro")
        LocalDateTime fechaHoraFin,

        @NotNull(message = "Debe indicar el deporte para el que se reserva la cancha")
        Deporte deporteSeleccionado
) {
}
