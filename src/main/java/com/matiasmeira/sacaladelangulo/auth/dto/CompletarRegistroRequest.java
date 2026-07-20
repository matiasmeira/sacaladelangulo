package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO para el paso 3 del registro en 2 pasos: token de verificación ya validado en el
 * paso 2, junto con los datos personales y la contraseña del jugador.
 */
public record CompletarRegistroRequest(
        @NotBlank(message = "El token es obligatorio")
        String token,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        String telefono,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "La contraseña debe contener al menos una letra y un número")
        String password
) {
}
