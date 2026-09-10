package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.util.List;
import java.util.Set;

/**
 * Disponibilidad de una cancha para un día puntual, desglosada por duración de turno.
 *
 * {@code ocupadaPorPool} lista los rangos en los que esta cancha no es reservable por
 * consumo de pool AJENO (no por bloqueo ni por su propia reserva, ya reflejados en
 * slotsLibres). Es {@code null} cuando no se pidió este dato (ver
 * DisponibilidadService#obtenerDisponibilidad) — la disponibilidad pública no lo pide,
 * para no exponer la ocupación interna del complejo — y una lista (vacía o no) cuando
 * sí se pidió.
 */
public record DisponibilidadCanchaResponse(
        Long canchaId,
        String canchaNombre,
        Set<Deporte> deportes,
        List<DisponibilidadDuracionResponse> opcionesDuracion,
        List<RangoOcupadoResponse> ocupadaPorPool
) {
}
