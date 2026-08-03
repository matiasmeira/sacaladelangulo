package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Se publica cuando un jugador termina el registro en 2 pasos (ver
 * RegistroVerificacionService.completarRegistro), para disparar el email de bienvenida
 * desacoplado de la transacción que crea el Usuario (ver RegistroVerificacionEmailListener).
 */
public record RegistroCompletadoEvent(String email, String nombre) {
}
