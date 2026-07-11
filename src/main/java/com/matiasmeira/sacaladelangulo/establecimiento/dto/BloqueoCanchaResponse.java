package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.LocalDateTime;
import java.util.List;

public record BloqueoCanchaResponse(
        Long id,
        Long canchaId,
        LocalDateTime fechaInicio,
        LocalDateTime fechaFin,
        String motivo,
        List<ReservaAfectadaResponse> reservasAfectadas
) {
}
