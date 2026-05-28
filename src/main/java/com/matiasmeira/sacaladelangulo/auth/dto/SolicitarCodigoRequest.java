package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para solicitar un código OTP de verificación de teléfono.
 */
public record SolicitarCodigoRequest(
        @NotBlank(message = "El teléfono es obligatorio")
        String telefono
) {
}
