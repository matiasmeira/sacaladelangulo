package com.matiasmeira.sacaladelangulo.empleado.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CambiarPinRequest(
        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
        String pin
) {
}
