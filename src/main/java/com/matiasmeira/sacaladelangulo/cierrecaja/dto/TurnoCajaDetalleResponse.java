package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import java.util.List;

/**
 * Detalle completo de un turno de caja (getDetalleTurno): datos del turno más todos
 * sus movimientos, ordenados cronológicamente.
 */
public record TurnoCajaDetalleResponse(
        TurnoCajaResponse turno,
        List<MovimientoCajaResponse> movimientos
) {
}
