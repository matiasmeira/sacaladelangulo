package com.matiasmeira.sacaladelangulo.reserva.service;

import java.util.List;

/**
 * Evento publicado UNA sola vez por cada turno fijo cancelado, con los ids de las
 * ocurrencias efectivamente dadas de baja.
 *
 * <p>Mismo motivo que {@link TurnoFijoCreadoEvent}: publicar un ReservaCanceladaEvent por
 * ocurrencia encolaba una tarea @Async por fecha contra el pool de AsyncConfig (core 2,
 * max 5, cola 50, AbortPolicy). Una serie anual cancelada de entrada son ~52 tareas, más de
 * lo que la cola aguanta junto con el resto de los @Async del sistema. Y para el destinatario
 * es UN aviso ("se dio de baja tu turno de los martes") con la lista de fechas, no 52 mails.
 *
 * @param turnoFijoId Id de la serie cancelada
 * @param reservaIds  Ids de las ocurrencias canceladas, en orden cronológico
 */
public record TurnoFijoCanceladoEvent(Long turnoFijoId, List<Long> reservaIds) {
}
