package com.matiasmeira.sacaladelangulo.reserva.model;

/**
 * Método de pago con el que se saldó una reserva al finalizarla (ver
 * ReservaService.finalizarReserva). Nulo hasta ese momento.
 */
public enum MetodoPago {
    EFECTIVO,
    TRANSFERENCIA,
    MERCADO_PAGO,
    TARJETA_DEBITO,
    TARJETA_CREDITO
}
