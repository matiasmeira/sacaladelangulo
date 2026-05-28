package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para verificar un código OTP de teléfono.
 */
public record VerificarCodigoRequest(
        @NotBlank(message = "El código es obligatorio")
        String codigo
) {
}
