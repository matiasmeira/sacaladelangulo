package com.matiasmeira.sacaladelangulo.reserva.model;

/**
 * Enum que representa los estados posibles de una reserva.
 */
public enum EstadoReserva {
    /**
     * Reserva pendiente de pago de seña.
     */
    PENDIENTE_SENA,

    /**
     * Reserva confirmada (seña pagada).
     */
    CONFIRMADA,

    /**
     * Reserva cancelada.
     */
    CANCELADA,

    /**
     * Reserva finalizada (servicio completado).
     */
    FINALIZADA
}
