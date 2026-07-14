package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.util.List;

/**
 * Turnos libres para una de las duraciones reservables de una cancha (una cancha puede
 * aceptar más de una duración, ej. 60 y 90 minutos).
 */
public record DisponibilidadDuracionResponse(
        Integer duracionMinutos,
        List<SlotDisponibleResponse> slotsLibres
) {
}
