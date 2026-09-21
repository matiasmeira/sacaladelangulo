package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;

/**
 * DTO de respuesta para un establecimiento (cara del propio dueño/admin dueño). Incluye
 * estadoVerificacion y motivoRechazo -- el dueño tiene que poder leer por qué lo rechazaron
 * -- y los datos ya cargados de la solicitud de verificación (cuit, razonSocial,
 * telefonoContacto, urlRedSocial), para que el front pueda precargar el formulario al
 * resolicitar tras un rechazo.
 */
public record EstablecimientoResponse(
        Long id,
        String nombre,
        String direccion,
        Double latitud,
        Double longitud,
        Boolean requiereSena,
        Boolean requiereTelefonoVerificado,
        Boolean isActive,
        Long duenoId,
        java.util.List<HorarioAtencionDto> horariosAtencion,
        java.util.Set<Servicio> servicios,
        Double promedioCalificacion,
        Long cantidadCalificaciones,
        FeedbackDestacadoDto comentarioDestacado,
        EstadoVerificacion estadoVerificacion,
        String cuit,
        String razonSocial,
        String telefonoContacto,
        String urlRedSocial,
        String motivoRechazo
) {
}
