package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;

/**
 * Se publica cuando un jugador o dueño termina el registro en 2 pasos (ver
 * RegistroVerificacionService.completarRegistro), para disparar el email de bienvenida
 * desacoplado de la transacción que crea el Usuario (ver RegistroVerificacionEmailListener).
 */
public record RegistroCompletadoEvent(String email, String nombre, Role rol) {
}
