package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.LocalDateTime;

public record BloqueoCanchaRequest(
        LocalDateTime fechaInicio,
        LocalDateTime fechaFin,
        String motivo
) {
}
