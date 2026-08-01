package com.matiasmeira.sacaladelangulo.reportes.dto;

import com.matiasmeira.sacaladelangulo.reserva.model.MetodoPago;

import java.math.BigDecimal;

public record DesglosePorMetodoPagoDto(
        MetodoPago metodoPago,
        Comparativo<BigDecimal> monto,
        Comparativo<Long> cantidadReservas
) {
}
