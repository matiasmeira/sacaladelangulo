package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * DTO de respuesta para la política de cancelación de un establecimiento.
 * reservasFuturasAfectadas solo viaja en la respuesta del PATCH (cuántas reservas
 * futuras quedan bajo la nueva política); en el GET siempre es null.
 */
public record PoliticaCancelacionResponse(
        Integer horasCancelacionAntesPartido,
        Integer minutosGraciaCancelacion,
        Integer reservasFuturasAfectadas
) {
}
