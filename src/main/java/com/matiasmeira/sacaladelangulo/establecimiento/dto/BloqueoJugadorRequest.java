package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BloqueoJugadorRequest(
        @NotNull(message = "El ID del jugador es obligatorio")
        Long jugadorId,

        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String motivo
) {
}
