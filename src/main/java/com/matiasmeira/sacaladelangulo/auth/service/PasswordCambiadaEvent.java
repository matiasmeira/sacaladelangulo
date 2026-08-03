package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Se publica cuando la contraseña de un usuario cambió por el flujo de recuperación (ver
 * RecuperacionPasswordService.resetPassword), para avisarle por email fuera de la
 * transacción que hizo el cambio (ver RecuperacionPasswordEmailListener).
 */
public record PasswordCambiadaEvent(String email) {
}
