package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;

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
        Long duenoId,
        java.util.List<HorarioAtencionDto> horariosAtencion,
        java.util.Set<Servicio> servicios,
        Double promedioCalificacion,
        Long cantidadCalificaciones,
        FeedbackDestacadoDto comentarioDestacado
) {
}
