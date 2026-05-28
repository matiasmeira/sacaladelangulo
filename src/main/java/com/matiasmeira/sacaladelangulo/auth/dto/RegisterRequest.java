package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para la recepción de datos al registrar un nuevo usuario.
 */
public record RegisterRequest(
        @Email(message = "El email debe ser válido")
        @NotBlank(message = "El email es obligatorio")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        String password,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre
) {
}
