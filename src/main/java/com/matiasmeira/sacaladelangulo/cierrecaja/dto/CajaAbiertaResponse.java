package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Estado en vivo del turno de caja actualmente ABIERTO de un establecimiento: saldo
 * teórico de efectivo calculado hasta el momento y totales de ingresos/egresos
 * desglosados por método de pago.
 */
public record CajaAbiertaResponse(
        TurnoCajaResponse turno,
        BigDecimal saldoTeoricoEfectivo,
        Map<MetodoPago, BigDecimal> totalIngresosPorMetodoPago,
        Map<MetodoPago, BigDecimal> totalEgresosPorMetodoPago
) {
}
