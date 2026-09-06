package com.matiasmeira.sacaladelangulo.reserva.service;

/**
 * Evento publicado cada vez que UNA Reserva suelta queda en estado CONFIRMADA (transición
 * PENDIENTE_SENA -> CONFIRMADA en confirmarReserva, o creación directa en CONFIRMADA vía
 * crearReservaManual).
 *
 * <p>TurnoFijoService.crear NO usa este evento: un turno fijo publica un único
 * {@link TurnoFijoCreadoEvent} con todas sus ocurrencias, para mandar un solo aviso con la
 * lista de fechas en vez de uno por fecha. Ver ahí el detalle.
 *
 * <p>Solo lleva el ID: el listener corre @Async fuera
 * de la transacción original, así que no puede recibir la entidad detached (ver
 * ReservaNotificacionListener, que la vuelve a cargar con sus asociaciones necesarias).
 *
 * @param reservaId ID de la reserva confirmada
 */
public record ReservaConfirmadaEvent(Long reservaId) {
}
