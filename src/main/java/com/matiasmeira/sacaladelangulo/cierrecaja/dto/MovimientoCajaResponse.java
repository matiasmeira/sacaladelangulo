package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Representación de un movimiento (ingreso/egreso) dentro de un turno de caja.
 */
public record MovimientoCajaResponse(
        Long id,
        Long turnoCajaId,
        String tipo,
        String origen,
        String metodoPago,
        BigDecimal monto,
        String descripcion,
        Long referenciaId,
        LocalDateTime fechaHora,
        Long usuarioId,
        String usuarioNombre
) {
}
