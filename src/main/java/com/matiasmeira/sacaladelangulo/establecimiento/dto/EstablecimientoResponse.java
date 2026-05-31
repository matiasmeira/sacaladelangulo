package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * DTO de respuesta para un establecimiento.
 */
public record EstablecimientoResponse(
        Long id,
        String nombre,
        String direccion,
        Double latitud,
        Double longitud,
        Boolean requiereSena,
        Boolean isActive,
        Long duenoId
) {
}
