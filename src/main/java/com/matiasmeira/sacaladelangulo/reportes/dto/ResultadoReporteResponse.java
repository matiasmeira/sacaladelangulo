package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;

public record ResultadoReporteResponse(
        RangoFechas periodoActual,
        RangoFechas periodoAnterior,
        Comparativo<BigDecimal> totalFacturado,
        Comparativo<BigDecimal> totalGastos,
        Comparativo<BigDecimal> neto
) {
}
