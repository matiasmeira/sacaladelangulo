package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body de POST /api/v1/establecimientos/{id}/solicitar-verificacion. Los cuatro campos son
 * obligatorios ACÁ: en Establecimiento siguen siendo nullable porque recién se exigen al
 * pedir la verificación, no al crear el establecimiento.
 */
public record SolicitarVerificacionRequest(
        @NotBlank(message = "El CUIT es obligatorio")
        String cuit,

        @NotBlank(message = "La razón social es obligatoria")
        String razonSocial,

        @NotBlank(message = "El teléfono de contacto es obligatorio")
        String telefonoContacto,

        @NotBlank(message = "La URL de Instagram o Facebook es obligatoria")
        String urlRedSocial
) {
}
