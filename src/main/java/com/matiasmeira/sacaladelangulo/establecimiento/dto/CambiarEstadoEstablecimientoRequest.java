package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body de PATCH /api/v1/establecimientos/{id}/estado.
 */
public record CambiarEstadoEstablecimientoRequest(
        @NotNull(message = "Debe indicar el estado deseado")
        Boolean activo
) {
}
