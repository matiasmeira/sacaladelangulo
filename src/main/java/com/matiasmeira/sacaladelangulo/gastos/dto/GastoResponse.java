package com.matiasmeira.sacaladelangulo.gastos.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record GastoResponse(
        Long id,
        Long establecimientoId,
        LocalDate fecha,
        BigDecimal monto,
        String categoria,
        String descripcion,
        String metodoPago,
        String comprobanteUrl,
        Long usuarioRegistroId,
        String usuarioRegistroNombre,
        LocalDateTime fechaCreacion
) {
}
