package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.LocalDateTime;

public record BloqueoJugadorResponse(
        Long id,
        Long jugadorId,
        String jugadorNombre,
        String jugadorEmail,
        String motivo,
        LocalDateTime fechaBloqueo
) {
}
