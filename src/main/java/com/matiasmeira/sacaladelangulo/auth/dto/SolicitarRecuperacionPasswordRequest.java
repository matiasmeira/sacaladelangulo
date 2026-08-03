package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para el paso 1 de la recuperación de contraseña: solo el email al que se le va a
 * enviar el link/código de recuperación. Deliberadamente distinto de SolicitarCodigoRequest,
 * que es del flujo no relacionado de OTP de teléfono.
 */
public record SolicitarRecuperacionPasswordRequest(
        @Email(message = "El email debe ser válido")
        @NotBlank(message = "El email es obligatorio")
        String email
) {
}
