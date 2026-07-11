package com.matiasmeira.sacaladelangulo.empleado.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;

import java.util.Set;

public record EmpleadoResponse(
        Long id,
        String nombre,
        Set<PermisoEmpleado> permisos,
        Boolean activo,
        Long establecimientoId
) {
}
