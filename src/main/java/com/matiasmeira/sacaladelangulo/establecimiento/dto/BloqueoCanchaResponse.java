package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import java.time.LocalDateTime;
import java.util.List;

public record BloqueoCanchaResponse(
        Long id,
        Long canchaId,
        LocalDateTime fechaInicio,
        LocalDateTime fechaFin,
        String motivo,
        List<ReservaResponse> reservasAfectadas
) {
}
