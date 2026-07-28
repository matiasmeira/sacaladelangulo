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
     * Reserva cancelada (por el jugador, el dueño o un empleado autorizado).
     */
    CANCELADA,

    /**
     * Reserva liberada automáticamente porque venció la ventana de 10 minutos para
     * confirmar/pagar la seña (ver Reserva.expiraEn y ReservaExpiracionService). Se
     * distingue de CANCELADA para que la auditoría pueda diferenciar un abandono
     * (nadie confirmó a tiempo) de una cancelación explícita.
     */
    CANCELADA_PRERESERVA,

    /**
     * Reserva finalizada (servicio completado).
     */
    FINALIZADA
}
