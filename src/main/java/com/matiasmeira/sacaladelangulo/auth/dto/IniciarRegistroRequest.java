package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para el paso 1 del registro en 2 pasos: solo el email a verificar.
 */
public record IniciarRegistroRequest(
        @Email(message = "El email debe ser válido")
        @NotBlank(message = "El email es obligatorio")
        String email
) {
}
