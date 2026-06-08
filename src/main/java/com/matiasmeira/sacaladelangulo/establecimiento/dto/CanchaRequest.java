package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        Integer capacidad,

        @NotNull(message = "El precio base es obligatorio")
        BigDecimal precioBase,

        BigDecimal montoSena,

        List<Integer> duracionesPermitidas,

        Boolean permiteInicioMediaHora,

        List<TarifaDto> tarifas,

        List<Long> canchasFisicasIds,

        Integer cantidadCanchasNecesarias
) {
}