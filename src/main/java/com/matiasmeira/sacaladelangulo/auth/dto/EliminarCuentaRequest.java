package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para DELETE /api/v1/usuarios/me: reconfirma la identidad con la contraseña actual
 * antes de anonimizar la cuenta (ver UsuarioEliminacionService.autoeliminar).
 */
public record EliminarCuentaRequest(
        @NotBlank(message = "La contraseña es obligatoria")
        String password
) {
}
