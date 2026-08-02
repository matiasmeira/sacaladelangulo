package com.matiasmeira.sacaladelangulo.caja.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsumirCodigoRequest(
        @NotBlank(message = "El código es obligatorio")
        String codigo
) {
}
