package com.matiasmeira.sacaladelangulo.buffet.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DTO para ajustar el stock de un producto. La cantidad puede ser positiva (ingreso
 * de mercadería) o negativa (venta/merma); el stock resultante nunca puede ser negativo.
 */
public record AjustarStockRequest(
        @NotNull(message = "La cantidad es obligatoria")
        Integer cantidad
) {
}
