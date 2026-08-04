package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO para abrir un turno de caja. dispositivoId es opcional: identifica la caja
 * mostrador (DispositivoCaja) desde la que se abre, si corresponde.
 */
public record AbrirCajaRequest(
        @NotNull(message = "El fondo inicial es obligatorio")
        @DecimalMin(value = "0.0", inclusive = true, message = "El fondo inicial no puede ser negativo")
        BigDecimal fondoInicial,

        Long dispositivoId
) {
}
