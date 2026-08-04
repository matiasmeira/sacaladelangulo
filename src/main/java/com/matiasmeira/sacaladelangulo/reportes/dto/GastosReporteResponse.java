package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

public record GastosReporteResponse(
        RangoFechas periodoActual,
        RangoFechas periodoAnterior,
        Comparativo<BigDecimal> totalGastado,
        List<DesglosePorCategoriaDto> desglosePorCategoria,
        List<PuntoGastoDiariaDto> serieTemporal
) {
}
