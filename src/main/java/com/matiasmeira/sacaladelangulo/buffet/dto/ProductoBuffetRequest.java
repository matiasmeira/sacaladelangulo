package com.matiasmeira.sacaladelangulo.buffet.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO para crear o actualizar un producto de buffet. En la actualización solo se
 * aplican nombre, descripción y precio; el stock se maneja exclusivamente a través
 * del endpoint de ajuste (PATCH .../stock).
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
        Integer stock
) {
}
