package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record HorarioAtencionDto(
        DayOfWeek diaSemana,
        LocalTime horaApertura,
        LocalTime horaCierre
) {
}
