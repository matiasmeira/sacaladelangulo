package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VentaResumenResponse(
        Long id,
        LocalDateTime fechaHora,
        BigDecimal total,
        String estado,
        String metodoPago,
        Long reservaId
) {
}
