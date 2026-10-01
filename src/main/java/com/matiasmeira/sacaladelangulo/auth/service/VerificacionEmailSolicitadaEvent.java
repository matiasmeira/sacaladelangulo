package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;

/**
 * Se publica cuando se genera un token de verificación de registro, para que el envío del
 * email ocurra desacoplado de la transacción que lo persiste (ver RegistroVerificacionEmailListener).
 */
public record VerificacionEmailSolicitadaEvent(String email, String linkVerificacion, String codigo, Role rol) {
}
