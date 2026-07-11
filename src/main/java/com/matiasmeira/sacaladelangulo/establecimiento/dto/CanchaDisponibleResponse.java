package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * Cancha alternativa (mismo establecimiento, deporte y capacidad) libre en el horario
 * de una reserva afectada por un bloqueo, ofrecida como opción de reubicación.
 */
public record CanchaDisponibleResponse(
        Long id,
        String nombre
) {
}
