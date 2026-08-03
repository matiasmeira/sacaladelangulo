package com.matiasmeira.sacaladelangulo.mails.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para darse de baja de los emails de marketing a partir del token opaco recibido
 * en el link del email (ver Usuario.unsubscribeToken).
 */
public record BajaMarketingRequest(
        @NotBlank(message = "El token es obligatorio")
        String token
) {
}
