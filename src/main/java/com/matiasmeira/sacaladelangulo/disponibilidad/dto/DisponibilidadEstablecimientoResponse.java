package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Grilla consolidada de turnos disponibles de un establecimiento para un rango de
 * fechas (puede ser un único día, cuando fechaInicio == fechaFin).
 */
public record DisponibilidadEstablecimientoResponse(
        Long establecimientoId,
        LocalDate fechaInicio,
        LocalDate fechaFin,
        List<DisponibilidadDiaResponse> dias
) {
}
