package com.matiasmeira.sacaladelangulo.reserva.model;

/**
 * Estado de la REGLA de un turno fijo, que es distinto del estado de cada ocurrencia.
 * Una serie ACTIVA puede tener ocurrencias canceladas sueltas (el dueño dio de baja un
 * feriado); una serie CANCELADA dejó de generar compromiso a partir de canceladoDesde.
 */
public enum EstadoTurnoFijo {
    ACTIVO,
    CANCELADO
}
