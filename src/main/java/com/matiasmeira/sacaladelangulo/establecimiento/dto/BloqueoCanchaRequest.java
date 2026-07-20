package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record BloqueoCanchaRequest(
        @NotNull(message = "La fecha de inicio es obligatoria")
        @FutureOrPresent(message = "La fecha de inicio no puede ser en el pasado")
        LocalDateTime fechaInicio,

        @NotNull(message = "La fecha de fin es obligatoria")
        @FutureOrPresent(message = "La fecha de fin no puede ser en el pasado")
        LocalDateTime fechaFin,

        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String motivo
) {
}
