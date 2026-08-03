package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Se publica cuando se genera un token de recuperación de contraseña, para que el envío del
 * email ocurra desacoplado de la transacción que lo persiste (ver RecuperacionPasswordEmailListener).
 */
public record RecuperacionPasswordSolicitadaEvent(String email, String linkRecuperacion, String codigo) {
}
