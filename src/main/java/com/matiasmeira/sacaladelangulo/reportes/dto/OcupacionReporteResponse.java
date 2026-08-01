package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

public record OcupacionReporteResponse(
        RangoFechas periodoActual,
        RangoFechas periodoAnterior,
        Comparativo<BigDecimal> porcentajeOcupacionGeneral,
        List<OcupacionPorFranjaDto> ocupacionPorFranja,
        List<OcupacionPorCanchaDto> ocupacionPorCancha,
        String notaMetodologica
) {
}
