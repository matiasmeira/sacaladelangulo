package com.matiasmeira.sacaladelangulo.cliente.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ClienteResponse(
        Long jugadorId,
        String nombre,
        String telefono,
        String email,
        long reservasTotales,
        LocalDateTime ultimaReserva,
        long ausencias,
        BigDecimal totalGastado,
        Boolean bloqueado
) {
}
