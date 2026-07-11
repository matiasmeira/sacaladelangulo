package com.matiasmeira.sacaladelangulo.empleado.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Set;

/**
 * DTO para crear un empleado: nombre (único dentro del establecimiento) y PIN
 * de 4 dígitos para el login de mostrador. Los permisos son opcionales al crear
 * (por defecto ninguno) y se pueden ajustar después.
 */
public record EmpleadoRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
        String pin,

        Set<PermisoEmpleado> permisos
) {
}
