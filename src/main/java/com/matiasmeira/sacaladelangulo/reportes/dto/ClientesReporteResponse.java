package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.util.List;

public record ClientesReporteResponse(
        RangoFechas periodoActual,
        RangoFechas periodoAnterior,
        Comparativo<Long> clientesNuevos,
        AusenciasInfo ausencias,
        List<TopClienteDto> topClientes
) {
}
