package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DTO para la creación de un turno fijo semanal: genera una reserva individual por
 * cada fecha del período que coincida con el día de la semana indicado. El cliente
 * se identifica con un jugador registrado (jugadorId) o, si no tiene cuenta, con los
 * datos de cliente manual (nombreClienteManual obligatorio en ese caso).
 */
public record ReservaSemanalRequest(
        @NotNull(message = "El ID de la cancha es obligatorio")
        Long canchaId,

        @NotNull(message = "La fecha de inicio del período es obligatoria")
        @FutureOrPresent(message = "La fecha de inicio del período debe ser en el presente o futuro")
        LocalDate fechaInicioPeriodo,

        @NotNull(message = "La fecha de fin del período es obligatoria")
        LocalDate fechaFinPeriodo,

        @NotNull(message = "El día de la semana es obligatorio")
        DayOfWeek diaSemana,

        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime horaInicio,

        @NotNull(message = "La hora de fin es obligatoria")
        LocalTime horaFin,

        @NotNull(message = "Debe indicar el deporte para el que se reserva la cancha")
        Deporte deporteSeleccionado,

        Long jugadorId,

        String nombreClienteManual,

        String telefonoClienteManual
) {
}
