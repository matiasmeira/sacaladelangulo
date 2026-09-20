package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body de POST /api/v1/admin/establecimientos/{id}/rechazar. El motivo es obligatorio: se
 * le muestra tal cual al dueño en el email de rechazo (ver
 * EstablecimientoVerificacionEmailListener), así que un rechazo sin motivo lo deja sin
 * saber qué corregir para resolicitar la verificación.
 */
public record RechazarEstablecimientoRequest(
        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 255, message = "El motivo no puede superar los 255 caracteres")
        String motivo
) {
}
