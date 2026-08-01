package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;

/**
 * DTO para crear una cancha.
 */
public record CanchaRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotEmpty(message = "Debe indicar al menos un deporte")
        Set<Deporte> deportes,

        @NotNull(message = "La capacidad es obligatoria")
        @Positive(message = "La capacidad debe ser mayor a 0")
        Integer capacidad,

        @NotNull(message = "El precio base es obligatorio")
        @Positive(message = "El precio base debe ser mayor a 0")
        BigDecimal precioBase,

        @PositiveOrZero(message = "El monto seña no puede ser negativo")
        BigDecimal montoSena,

        List<Integer> duracionesPermitidas,

        /**
         * Precio exacto opcional por duración (minutos -> precio) a nivel de la cancha,
         * usado cuando no hay ninguna Tarifa aplicable para el día/horario de la reserva.
         * Si se omite o no incluye una duración, esa duración se calcula proporcional
         * sobre precioBase (comportamiento actual sin cambios).
         */
        Map<Integer, BigDecimal> preciosPorDuracion,

        Boolean permiteInicioMediaHora,

        @Valid List<TarifaDto> tarifas,

        List<Long> canchasFisicasIds,

        Integer cantidadCanchasNecesarias
) {
}