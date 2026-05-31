package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.util.List;

/**
 * DTO de respuesta para una cancha.
 */
public record CanchaResponse(
        Long id,
        String nombre,
        String deporte,
        Integer capacidad,
        Boolean isActive,
        Long establecimientoId,
        List<Long> canchasFisicasIds,
        Integer cantidadCanchasNecesarias
) {
}
