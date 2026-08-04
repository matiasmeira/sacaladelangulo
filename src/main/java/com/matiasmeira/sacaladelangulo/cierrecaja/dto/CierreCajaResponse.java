package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Resultado del cierre de un turno de caja: saldo teórico calculado, saldo real
 * contado y la diferencia entre ambos. resultado es "SOBRANTE" si diferencia > 0,
 * "FALTANTE" si diferencia < 0, o "EXACTO" si coinciden.
 */
public record CierreCajaResponse(
        Long turnoId,
        Long establecimientoId,
        LocalDateTime fechaCierre,
        BigDecimal fondoInicial,
        BigDecimal saldoTeoricoEfectivo,
        BigDecimal saldoRealContado,
        BigDecimal diferencia,
        String resultado,
        String observaciones
) {
}
