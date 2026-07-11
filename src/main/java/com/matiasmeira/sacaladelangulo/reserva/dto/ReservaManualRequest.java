package com.matiasmeira.sacaladelangulo.reserva.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * DTO para la creación de una reserva de mostrador (registrada por el dueño para
 * un cliente presencial o telefónico sin cuenta en la plataforma).
 */
public record ReservaManualRequest(
        @NotNull(message = "El ID de la cancha es obligatorio")
        Long canchaId,

        @NotNull(message = "La fecha y hora de inicio es obligatoria")
        @FutureOrPresent(message = "La fecha y hora de inicio debe ser en el presente o futuro")
        LocalDateTime fechaHoraInicio,

        @NotNull(message = "La fecha y hora de fin es obligatoria")
        @FutureOrPresent(message = "La fecha y hora de fin debe ser en el presente o futuro")
        LocalDateTime fechaHoraFin,

        @NotBlank(message = "El nombre del cliente es obligatorio")
        String nombreCliente,

        String telefonoCliente,

        Boolean senaFisicaRecibida
) {
}
