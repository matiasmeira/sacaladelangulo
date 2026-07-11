package com.matiasmeira.sacaladelangulo.empleado.dto;

/**
 * Proyección mínima y pública para la pantalla de mostrador (elegir empleado
 * antes de ingresar el PIN): no expone permisos ni ningún otro dato sensible.
 */
public record EmpleadoNombreResponse(
        Long id,
        String nombre
) {
}
