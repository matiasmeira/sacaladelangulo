package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fila resumida de un turno de caja para el listado histórico (listarTurnos), sin
 * incluir el detalle de movimientos.
 */
public record TurnoCajaResumenResponse(
        Long id,
        Long establecimientoId,
        LocalDateTime fechaApertura,
        LocalDateTime fechaCierre,
        String estado,
        BigDecimal fondoInicial,
        BigDecimal saldoRealContado,
        BigDecimal diferencia,
        String usuarioAperturaNombre
) {
}
