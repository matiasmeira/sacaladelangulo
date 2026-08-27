package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * DTO para crear/actualizar un establecimiento.
 *
 * servicios == null significa "no modificar" (dejar los existentes intactos);
 * servicios == Set.of() significa "borrar todos". Necesario porque el frontend hace
 * PUT del objeto entero desde formularios por sección: si null se tratara como
 * "vaciar", cualquier PUT que no toque servicios (p. ej. editar horarios) los borraría.
 */
public record EstablecimientoRequest(
        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "La dirección es obligatoria")
        String direccion,

        @NotNull(message = "La latitud es obligatoria")
        Double latitud,

        @NotNull(message = "La longitud es obligatoria")
        Double longitud,

        @NotNull(message = "Debe especificar si requiere seña")
        Boolean requiereSena,

        @NotNull(message = "Debe especificar si requiere teléfono verificado")
        Boolean requiereTelefonoVerificado,

        @Valid
        java.util.List<HorarioAtencionDto> horariosAtencion,

        java.util.Set<Servicio> servicios
) {
}