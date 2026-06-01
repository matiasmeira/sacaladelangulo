package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * DTO para representación de tarifas dinámicas de cancha.
 */
public record TarifaDto(
        DayOfWeek diaSemana,
        LocalTime horaInicio,
        LocalTime horaFin,
        BigDecimal precio
) {
}
