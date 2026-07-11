package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record HorarioAtencionDto(
        @NotNull(message = "El día de la semana es obligatorio")
        DayOfWeek diaSemana,

        @NotNull(message = "La hora de apertura es obligatoria")
        LocalTime horaApertura,

        @NotNull(message = "La hora de cierre es obligatoria")
        LocalTime horaCierre
) {
}
