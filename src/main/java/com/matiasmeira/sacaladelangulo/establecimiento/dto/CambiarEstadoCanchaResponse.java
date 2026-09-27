package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * A diferencia de CambiarEstadoEstablecimientoResponse, no trae estadoVerificacion (cancha no
 * tiene ese eje) ni reservasFuturasConfirmadas (acá desactivar YA bloquea si rompe una reserva
 * futura -- ver CanchaService.validarDesactivacion -- en vez de sólo informarlo).
 */
public record CambiarEstadoCanchaResponse(
        Long id,
        Boolean isActive
) {
}
