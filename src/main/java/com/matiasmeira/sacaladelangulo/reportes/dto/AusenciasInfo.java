package com.matiasmeira.sacaladelangulo.reportes.dto;

/**
 * Ausencias (no-show) del establecimiento en el rango del reporte: cantidad de reservas
 * marcadas EstadoReserva.AUSENTE (ver ReservaService.marcarAusente). El campo `disponible`
 * se mantiene por compatibilidad con la forma de respuesta ya expuesta al front.
 */
public record AusenciasInfo(
        boolean disponible,
        Long total,
        String motivoNoDisponible
) {
}
