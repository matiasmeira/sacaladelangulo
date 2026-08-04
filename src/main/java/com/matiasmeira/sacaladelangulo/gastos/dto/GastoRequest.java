package com.matiasmeira.sacaladelangulo.gastos.dto;

import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GastoRequest(
        @NotNull(message = "La fecha del gasto es obligatoria")
        LocalDate fecha,

        @NotNull(message = "El monto es obligatorio")
        @DecimalMin(value = "0.0", inclusive = false, message = "El monto debe ser mayor a 0")
        BigDecimal monto,

        @NotNull(message = "La categoría es obligatoria")
        CategoriaGasto categoria,

        @NotBlank(message = "La descripción es obligatoria")
        String descripcion,

        @NotNull(message = "El método de pago es obligatorio")
        MetodoPago metodoPago,

        String comprobanteUrl
) {
}
