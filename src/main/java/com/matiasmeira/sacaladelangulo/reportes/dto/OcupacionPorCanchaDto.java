package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;

public record OcupacionPorCanchaDto(
        Long canchaId,
        String canchaNombre,
        Comparativo<BigDecimal> porcentajeOcupacion,
        Comparativo<BigDecimal> horasReservadas,
        Comparativo<BigDecimal> horasDisponibles
) {
}
