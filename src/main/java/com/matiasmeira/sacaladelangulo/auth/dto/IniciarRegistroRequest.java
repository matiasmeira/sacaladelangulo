package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO para el paso 1 del registro en 2 pasos: el email a verificar y, opcionalmente, la
 * ruta interna del front a la que volver tras verificar (se ignora en silencio si no es
 * una ruta interna segura, ver UrlUtils.esRutaInternaSegura).
 */
public record IniciarRegistroRequest(
        @Email(message = "El email debe ser válido")
        @NotBlank(message = "El email es obligatorio")
        String email,
        String volverA
) {
    public IniciarRegistroRequest(String email) {
        this(email, null);
    }
}
