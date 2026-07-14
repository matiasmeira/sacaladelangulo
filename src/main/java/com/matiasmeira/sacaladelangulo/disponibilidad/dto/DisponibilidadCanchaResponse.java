package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.util.List;
import java.util.Set;

/**
 * Disponibilidad de una cancha para un día puntual, desglosada por duración de turno.
 */
public record DisponibilidadCanchaResponse(
        Long canchaId,
        String canchaNombre,
        Set<Deporte> deportes,
        List<DisponibilidadDuracionResponse> opcionesDuracion
) {
}
