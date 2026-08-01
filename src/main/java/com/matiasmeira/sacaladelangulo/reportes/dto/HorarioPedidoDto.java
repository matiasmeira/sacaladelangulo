package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.time.DayOfWeek;

public record HorarioPedidoDto(
        DayOfWeek diaSemana,
        int hora,
        long cantidadReservas
) {
}
