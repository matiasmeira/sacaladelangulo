package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;

/**
 * DTO para crear una cancha.
 */
public record CanchaRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El deporte es obligatorio")
        String deporte,

        @NotNull(message = "La capacidad es obligatoria")
        @Positive(message = "La capacidad debe ser mayor a 0")
        Integer capacidad,

        @NotNull(message = "El precio base es obligatorio")
        @Positive(message = "El precio base debe ser mayor a 0")
        BigDecimal precioBase,

        @PositiveOrZero(message = "El monto seña no puede ser negativo")
        BigDecimal montoSena,

        List<Integer> duracionesPermitidas,

        Boolean permiteInicioMediaHora,

        @Valid List<TarifaDto> tarifas,

        List<Long> canchasFisicasIds,

        Integer cantidadCanchasNecesarias
) {
}