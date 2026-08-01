package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;

public record OcupacionPorFranjaDto(
        FranjaHoraria franja,
        Comparativo<BigDecimal> porcentajeOcupacion,
        Comparativo<BigDecimal> horasReservadas,
        Comparativo<BigDecimal> horasDisponibles
) {
}
