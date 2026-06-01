package com.matiasmeira.sacaladelangulo.reserva.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para respuesta de reserva.
 */
public record ReservaResponse(
        Long id,
        Long jugadorId,
        String jugadorNombre,
        Long canchaId,
        String canchaNombre,
        LocalDateTime fechaHoraInicio,
        LocalDateTime fechaHoraFin,
        String estado,
        BigDecimal precioTotal,
        BigDecimal senaPagada
) {
}
