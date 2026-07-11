package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * DTO para el login de mostrador: el empleado toca su nombre (dentro del
 * establecimiento activo) e ingresa su PIN de 4 dígitos.
 */
public record EmpleadoLoginRequest(
        @NotNull(message = "El ID del establecimiento es obligatorio")
        Long establecimientoId,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
        String pin
) {
}
