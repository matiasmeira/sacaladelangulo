package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;

/**
 * DTO para PATCH /estado de una cancha. Mismo patrón que CambiarEstadoEstablecimientoRequest
 * (un único booleano obligatorio), salvo que acá conviven dos caminos para tocar isActive: éste
 * y el campo isActive (nullable) de CanchaRequest en el PUT general -- ver CanchaEstadoService.
 */
public record CambiarEstadoCanchaRequest(
        @NotNull(message = "Debe indicar el estado deseado")
        Boolean activo
) {
}
