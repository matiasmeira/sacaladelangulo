package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.util.List;

public record HorariosPedidosReporteResponse(
        RangoFechas periodo,
        List<HorarioPedidoDto> ranking
) {
}
