package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO para registrar un movimiento manual de caja (ingreso o egreso cargado a mano por
 * el operador). No incluye método de pago: los movimientos manuales siempre se
 * registran en EFECTIVO.
 */
public record MovimientoManualRequest(
        @NotNull(message = "El tipo de movimiento es obligatorio")
        TipoMovimientoCaja tipo,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a 0")
        BigDecimal monto,

        @NotBlank(message = "La descripción es obligatoria")
        String descripcion
) {
}
