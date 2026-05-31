package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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

        List<Long> canchasFisicasIds,

        Integer cantidadCanchasNecesarias
) {
}