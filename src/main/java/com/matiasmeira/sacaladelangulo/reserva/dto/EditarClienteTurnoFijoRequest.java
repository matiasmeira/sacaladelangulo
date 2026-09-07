package com.matiasmeira.sacaladelangulo.reserva.dto;

import jakarta.validation.constraints.NotBlank;

/** Corrección del cliente de una serie de mostrador (ver TurnoFijoService.editarCliente). */
public record EditarClienteTurnoFijoRequest(
        @NotBlank(message = "El nombre del cliente es obligatorio")
        String nombre,
        String telefono
) {
}
