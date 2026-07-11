package com.matiasmeira.sacaladelangulo.reserva.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DTO para reasignar una reserva existente a otra cancha del mismo establecimiento
 * (por ejemplo, cuando la cancha original fue bloqueada y hay una equivalente libre).
 */
public record MoverReservaRequest(
        @NotNull(message = "El ID de la nueva cancha es obligatorio")
        Long nuevaCanchaId
) {
}
