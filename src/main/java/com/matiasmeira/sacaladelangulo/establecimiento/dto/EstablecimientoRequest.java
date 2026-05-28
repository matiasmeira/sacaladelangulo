package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO para crear un establecimiento.
 */
public record EstablecimientoRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "La dirección es obligatoria")
        String direccion,

        @NotNull(message = "La latitud es obligatoria")
        Double latitud,

        @NotNull(message = "La longitud es obligatoria")
        Double longitud,

        @NotNull(message = "Debe especificar si requiere seña")
        Boolean requiereSena
) {
}