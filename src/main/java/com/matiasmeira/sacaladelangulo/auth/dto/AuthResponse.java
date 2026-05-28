package com.matiasmeira.sacaladelangulo.auth.dto;

/**
 * DTO de respuesta para devolver el token JWT tras la autenticación.
 */
public record AuthResponse(String token) {
}
