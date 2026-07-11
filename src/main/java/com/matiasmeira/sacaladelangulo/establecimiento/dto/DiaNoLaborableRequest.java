package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record DiaNoLaborableRequest(
        @NotNull(message = "La fecha es obligatoria")
        LocalDate fecha,

        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String motivo
) {
}
