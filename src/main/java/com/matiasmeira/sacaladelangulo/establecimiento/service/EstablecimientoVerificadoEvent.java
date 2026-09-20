package com.matiasmeira.sacaladelangulo.establecimiento.service;

/**
 * Publicado por AdminEstablecimientoVerificacionService cuando un admin aprueba la
 * verificación de un establecimiento. Lleva solo el ID del dueño (no la entidad) porque el
 * listener corre @Async en un hilo/persistence-context distinto al de la transacción que lo
 * publica -- mismo motivo que PruebaVencidaEvent/AvisoFinPruebaEvent.
 */
public record EstablecimientoVerificadoEvent(Long duenoId, String nombreEstablecimiento) {
}
