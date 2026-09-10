package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.time.LocalDateTime;

/**
 * Rango en el que una cancha no es reservable por consumo de pool AJENO (no por
 * bloqueo, ni por una reserva propia de esa cancha — esas ya se reflejan en
 * slotsLibres).
 */
public record RangoOcupadoResponse(LocalDateTime inicio, LocalDateTime fin) {
}
