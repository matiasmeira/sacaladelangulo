package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;

import java.time.LocalDateTime;

/**
 * Respuesta de POST /api/v1/establecimientos/{id}/solicitar-verificacion: confirma que la
 * solicitud (o resolicitud) quedó en cola. El resto de los datos del establecimiento se
 * consultan con GET /api/v1/establecimientos (EstablecimientoResponse ya expone
 * estadoVerificacion, motivoRechazo y los campos de verificación).
 */
public record SolicitarVerificacionResponse(
        Long id,
        EstadoVerificacion estadoVerificacion,
        LocalDateTime fechaSolicitudVerificacion
) {
}
