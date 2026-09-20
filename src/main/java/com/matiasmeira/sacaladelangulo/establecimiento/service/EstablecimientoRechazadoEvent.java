package com.matiasmeira.sacaladelangulo.establecimiento.service;

/**
 * Publicado por AdminEstablecimientoVerificacionService cuando un admin rechaza la
 * verificación de un establecimiento. Lleva solo el ID del dueño (no la entidad) por el
 * mismo motivo que EstablecimientoVerificadoEvent; el motivo del rechazo sí viaja completo
 * porque el listener lo necesita tal cual para el email, sin volver a consultarlo.
 */
public record EstablecimientoRechazadoEvent(Long duenoId, String nombreEstablecimiento, String motivo) {
}
