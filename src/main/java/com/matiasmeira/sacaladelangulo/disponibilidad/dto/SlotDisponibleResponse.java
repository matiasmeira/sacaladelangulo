package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.time.LocalDateTime;

/**
 * Turno de tiempo 100% libre para reservar (sin reservas, bloqueos, ni pool de canchas
 * ocupado en ese rango).
 */
public record SlotDisponibleResponse(
        LocalDateTime inicio,
        LocalDateTime fin
) {
}
