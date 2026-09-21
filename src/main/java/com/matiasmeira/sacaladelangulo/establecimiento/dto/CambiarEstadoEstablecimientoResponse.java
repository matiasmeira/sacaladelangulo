package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;

/**
 * Respuesta de PATCH /api/v1/establecimientos/{id}/estado. reservasFuturasConfirmadas
 * informa cuántas reservas CONFIRMADA (seña pagada) con fecha futura sigue teniendo el
 * establecimiento: deshabilitar no cancela ninguna (ver EstablecimientoEstadoService), así
 * que el dueño necesita saber que sigue teniendo esos compromisos vigentes con jugadores.
 */
public record CambiarEstadoEstablecimientoResponse(
        Long id,
        Boolean isActive,
        EstadoVerificacion estadoVerificacion,
        Long reservasFuturasConfirmadas
) {
}
