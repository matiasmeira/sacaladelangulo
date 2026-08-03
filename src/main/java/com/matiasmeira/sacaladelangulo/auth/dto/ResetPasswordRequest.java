package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO para el paso 2 de la recuperación de contraseña. Admite dos caminos mutuamente
 * excluyentes para identificar el token válido, igual que el registro admite verificar por
 * link o por código: o bien {@code token} (el que llegó en el link), o bien {@code email} +
 * {@code codigo} (el que el usuario tipeó a mano). Esa exclusividad es una regla cruzada
 * entre campos, no expresable con bean validation, así que se valida en
 * RecuperacionPasswordService.resetPassword.
 */
public record ResetPasswordRequest(
        String token,

        String email,

        String codigo,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "La contraseña debe contener al menos una letra y un número")
        String nuevaPassword
) {
}
