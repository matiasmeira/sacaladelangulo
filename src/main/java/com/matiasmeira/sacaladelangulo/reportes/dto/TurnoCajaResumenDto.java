package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Resumen de un turno de caja CERRADO para el reporte de diferencias de caja: fechas,
 * saldo teórico vs. contado y la diferencia resultante (positiva = sobrante, negativa = faltante).
 */
public record TurnoCajaResumenDto(
        Long turnoCajaId,
        LocalDateTime fechaApertura,
        LocalDateTime fechaCierre,
        BigDecimal saldoTeoricoEfectivo,
        BigDecimal saldoRealContado,
        BigDecimal diferencia
) {
}
