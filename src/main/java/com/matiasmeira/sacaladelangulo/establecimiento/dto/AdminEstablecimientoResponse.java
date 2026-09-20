package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;

import java.time.LocalDateTime;

/**
 * Fila del listado de admin para revisar solicitudes de verificación manual
 * (AdminEstablecimientoController#listar). A diferencia de EstablecimientoResponse (cara
 * del propio dueño) y de ComplejoCardResponse/ComplejoDetalleResponse (cara pública),
 * expone a propósito los datos de verificación (cuit, razón social, fechas) y al dueño
 * (id, nombre, email) -- información interna que ningún otro DTO de establecimiento
 * revela fuera del panel de admin.
 */
public record AdminEstablecimientoResponse(
        Long id,
        String nombre,
        String direccion,
        Double latitud,
        Double longitud,
        EstadoVerificacion estadoVerificacion,
        String cuit,
        String razonSocial,
        String telefonoContacto,
        String urlRedSocial,
        LocalDateTime fechaSolicitudVerificacion,
        LocalDateTime fechaVerificacion,
        String motivoRechazo,
        Long duenoId,
        String duenoNombre,
        String duenoEmail
) {
}
