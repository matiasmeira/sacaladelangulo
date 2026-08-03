package com.matiasmeira.sacaladelangulo.auth.dto;

/**
 * Respuesta de la verificación por código de registro: el token asociado, para que el
 * frontend pueda continuar con el flujo existente de POST /auth/registro/completar sin
 * cambios (idéntico al token que se obtiene del link de verificación).
 */
public record VerificarCodigoRegistroResponse(String token) {
}
