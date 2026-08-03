package com.matiasmeira.sacaladelangulo.reserva.service;

/**
 * Evento publicado cada vez que una Reserva transiciona realmente a estado CANCELADA (no se
 * publica en el no-op idempotente cuando ya estaba CANCELADA). Solo lleva el ID de la reserva
 * y el ID del usuario que ejecutó la cancelación (el "actor"): el listener corre @Async fuera
 * de la transacción original, así que no puede recibir la entidad detached (ver
 * ReservaNotificacionListener, que la vuelve a cargar con sus asociaciones necesarias) y
 * recalcula a partir del actorId si quien canceló fue el propio jugador.
 *
 * @param reservaId ID de la reserva cancelada
 * @param actorId   ID del usuario que realizó la cancelación
 */
public record ReservaCanceladaEvent(Long reservaId, Long actorId) {
}
