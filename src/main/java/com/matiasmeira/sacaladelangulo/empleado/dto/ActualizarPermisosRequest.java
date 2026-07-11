package com.matiasmeira.sacaladelangulo.empleado.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record ActualizarPermisosRequest(
        @NotNull(message = "Los permisos son obligatorios")
        Set<PermisoEmpleado> permisos
) {
}
