package com.matiasmeira.sacaladelangulo.reserva.service;

/**
 * Evento publicado cada vez que una Reserva queda en estado CONFIRMADA (transición
 * PENDIENTE_SENA -> CONFIRMADA en confirmarReserva, o creación directa en CONFIRMADA vía
 * crearReservaManual/crearReservaSemanal). Solo lleva el ID: el listener corre @Async fuera
 * de la transacción original, así que no puede recibir la entidad detached (ver
 * ReservaNotificacionListener, que la vuelve a cargar con sus asociaciones necesarias).
 *
 * @param reservaId ID de la reserva confirmada
 */
public record ReservaConfirmadaEvent(Long reservaId) {
}
