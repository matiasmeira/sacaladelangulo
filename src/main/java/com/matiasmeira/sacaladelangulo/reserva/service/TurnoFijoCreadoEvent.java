package com.matiasmeira.sacaladelangulo.reserva.service;

import java.util.List;

/**
 * Evento publicado UNA sola vez por cada turno fijo semanal creado (ver
 * TurnoFijoService.crear), con los IDs de todas sus ocurrencias.
 *
 * <p>Existe en vez de reusar {@link ReservaConfirmadaEvent} por ocurrencia porque un turno
 * fijo no son N reservas sueltas: es una sola decisión del dueño que el destinatario espera
 * recibir como un solo aviso con la lista de fechas. Publicar uno por ocurrencia mandaba un
 * email por fecha al jugador y otro al dueño — 104 emails para un turno fijo anual — y, peor,
 * encolaba una tarea @Async por ocurrencia contra el pool de AsyncConfig (core 2, max 5, cola
 * 50, AbortPolicy por defecto): un período de más de ~55 ocurrencias hacía que el executor
 * rechazara el resto, perdiendo notificaciones de reservas que ya habían commiteado, y de paso
 * encolaba detrás al resto de los @Async del sistema (verificación de email, recuperación de
 * contraseña, broadcast de marketing).
 *
 * <p>{@link ReservaConfirmadaEvent} sigue existiendo sin cambios para los caminos que sí son
 * de a una reserva: crearReservaManual y confirmarReserva.
 *
 * <p>Solo lleva los IDs, por el mismo motivo que ReservaConfirmadaEvent: el listener corre
 * @Async fuera de la transacción original, así que no puede recibir entidades detached (ver
 * ReservaNotificacionListener, que las vuelve a cargar con sus asociaciones necesarias).
 *
 * @param reservaIds IDs de las reservas creadas, en orden cronológico
 */
public record TurnoFijoCreadoEvent(List<Long> reservaIds) {
}
