package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record VentaResponse(
        Long id,
        LocalDateTime fechaHora,
        BigDecimal total,
        String estado,
        Long establecimientoId,
        Long reservaId,
        List<DetalleVentaResponse> detalles
) {
}
