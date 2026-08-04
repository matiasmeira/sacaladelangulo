package com.matiasmeira.sacaladelangulo.buffet.dto;

import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * DTO para registrar una venta de buffet. reservaId es opcional: permite cargar
 * el consumo a un turno específico.
 */
public record VentaRequest(
        @NotNull(message = "El ID del establecimiento es obligatorio")
        Long establecimientoId,

        Long reservaId,

        @NotNull(message = "El método de pago es obligatorio")
        MetodoPago metodoPago,

        @NotEmpty(message = "Debe incluir al menos un producto en la venta")
        @Valid
        List<DetalleVentaRequest> detalles
) {
}
