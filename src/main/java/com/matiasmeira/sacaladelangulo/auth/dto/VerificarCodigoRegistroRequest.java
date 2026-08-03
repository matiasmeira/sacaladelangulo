package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para verificar, mediante el código de 6 dígitos enviado por email, el token de
 * verificación de registro (alternativa a hacer click en el link, ver
 * RegistroVerificacionService.verificarCodigo). Deliberadamente distinto de
 * VerificarCodigoRequest, que es del flujo no relacionado de OTP de teléfono.
 */
public record VerificarCodigoRegistroRequest(
        @Email(message = "El email debe ser válido")
        @NotBlank(message = "El email es obligatorio")
        String email,

        @NotBlank(message = "El código es obligatorio")
        String codigo
) {
}
