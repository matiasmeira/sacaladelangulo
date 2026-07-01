package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * DTO para representación de tarifas dinámicas de cancha.
 */
public record TarifaDto(
        @NotNull(message = "El día de la semana es obligatorio")
        DayOfWeek diaSemana,

        @NotNull(message = "La hora de inicio es obligatoria")
        LocalTime horaInicio,

        @NotNull(message = "La hora de fin es obligatoria")
        LocalTime horaFin,

        @NotNull(message = "El precio es obligatorio")
        @Positive(message = "El precio debe ser mayor a 0")
        BigDecimal precio
) {
}
