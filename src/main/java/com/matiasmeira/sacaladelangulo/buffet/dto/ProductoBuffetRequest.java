package com.matiasmeira.sacaladelangulo.buffet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO para crear o actualizar un producto de buffet. En la actualización solo se
 * aplican nombre, descripción y precio; el stock se maneja exclusivamente a través
 * del endpoint de ajuste (PATCH .../stock) — ver el @Schema de stock, que documenta
 * esto también en el OpenAPI expuesto a integradores (ver B9 en la auditoría).
 */
public record ProductoBuffetRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        String descripcion,

        @NotNull(message = "El precio es obligatorio")
        @Min(value = 0, message = "El precio no puede ser negativo")
        BigDecimal precio,

        @NotNull(message = "El stock es obligatorio")
        @Min(value = 0, message = "El stock no puede ser negativo")
        @Schema(description = "Solo se aplica al crear el producto. En una actualización " +
                "(PUT) este valor se ignora: el stock se ajusta exclusivamente a través " +
                "del endpoint PATCH .../stock.")
        Integer stock
) {
}
