package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO para cerrar un turno de caja: saldoRealContado es el efectivo contado físicamente
 * por el operador al cierre, contra el que se compara el saldo teórico calculado.
 */
public record CerrarCajaRequest(
        @NotNull(message = "El saldo real contado es obligatorio")
        @DecimalMin(value = "0.0", inclusive = true, message = "El saldo real contado no puede ser negativo")
        BigDecimal saldoRealContado,

        String observaciones
) {
}
