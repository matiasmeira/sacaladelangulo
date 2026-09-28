package com.matiasmeira.sacaladelangulo.caja.dto;

import jakarta.validation.constraints.Size;

public record ActivarLocalRequest(
        @Size(max = 40, message = "El nombre no puede tener más de 40 caracteres")
        String label
) {
}
