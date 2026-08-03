package com.matiasmeira.sacaladelangulo.mails.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para el broadcast de una oferta a todos los usuarios con opt-in de marketing
 * (ver OfertaMarketingService), disparado manualmente por un ADMIN.
 */
public record EnviarOfertaRequest(
        @NotBlank(message = "El asunto es obligatorio")
        String asunto,

        @NotBlank(message = "El cuerpo es obligatorio")
        String cuerpoHtml
) {
}
