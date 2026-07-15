package com.matiasmeira.sacaladelangulo.auth.dto;

/**
 * DTO de respuesta para el paso 2 del registro en 2 pasos: confirma que el token de
 * verificación es válido y a qué email corresponde, para que el frontend avance de pantalla.
 */
public record VerificarTokenResponse(String email, Boolean verificado) {
}
