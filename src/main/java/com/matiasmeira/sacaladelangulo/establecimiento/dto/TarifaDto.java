package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;

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
        BigDecimal precio,

        /**
         * Precio exacto opcional por duración (minutos -> precio) dentro de esta franja
         * horaria/día. Si se omite o no incluye una duración, esa duración sigue
         * calculándose proporcional sobre `precio`.
         */
        Map<Integer, BigDecimal> preciosPorDuracion
) {
}
