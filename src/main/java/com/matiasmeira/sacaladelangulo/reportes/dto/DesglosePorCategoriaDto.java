package com.matiasmeira.sacaladelangulo.reportes.dto;

import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;

import java.math.BigDecimal;

public record DesglosePorCategoriaDto(
        CategoriaGasto categoria,
        Comparativo<BigDecimal> monto,
        Comparativo<Long> cantidad
) {
}
