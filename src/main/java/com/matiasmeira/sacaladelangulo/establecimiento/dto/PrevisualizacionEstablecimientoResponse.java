package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;

/**
 * Respuesta de GET /api/v1/establecimientos/{id}/previsualizacion: el MISMO
 * ComplejoDetalleResponse que vería un visitante público de este complejo si estuviera
 * habilitado y verificado, envuelto con el estadoVerificacion real y el flag
 * "previsualizacion" para que el front muestre el cartel correspondiente y desactive el
 * botón de reservar. Este endpoint nunca habilita reservar -- EstablecimientoOperativoGuard
 * sigue rechazando cualquier intento real de reserva sin importar qué devuelva esto.
 */
public record PrevisualizacionEstablecimientoResponse(
        ComplejoDetalleResponse detalle,
        EstadoVerificacion estadoVerificacion,
        boolean previsualizacion
) {
}
