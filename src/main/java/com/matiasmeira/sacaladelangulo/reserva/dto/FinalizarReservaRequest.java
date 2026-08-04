package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import jakarta.validation.constraints.NotNull;

public record FinalizarReservaRequest(
        @NotNull(message = "El método de pago es obligatorio")
        MetodoPago metodoPago
) {
}
