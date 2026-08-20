package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;

import java.util.List;

/**
 * Reserva impactada por un bloqueo, junto con las canchas del mismo establecimiento
 * (mismo deporte) que están libres en ese horario. Le permite al dueño
 * decidir, para cada reserva afectada, si la cancela (PUT /reservas/{id}/cancelar) o
 * la mueve a una de las alternativas disponibles (PUT /reservas/{id}/mover-cancha).
 */
public record ReservaAfectadaResponse(
        ReservaResponse reserva,
        List<CanchaDisponibleResponse> canchasAlternativasDisponibles
) {
}
