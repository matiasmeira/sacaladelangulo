package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Representación completa de un turno de caja, abierto o ya cerrado.
 */
public record TurnoCajaResponse(
        Long id,
        Long establecimientoId,
        Long dispositivoCajaId,
        String dispositivoCajaLabel,
        Long usuarioAperturaId,
        String usuarioAperturaNombre,
        LocalDateTime fechaApertura,
        BigDecimal fondoInicial,
        String estado,
        LocalDateTime fechaCierre,
        Long usuarioCierreId,
        String usuarioCierreNombre,
        BigDecimal saldoTeoricoEfectivo,
        BigDecimal saldoRealContado,
        BigDecimal diferencia,
        String observaciones
) {
}
