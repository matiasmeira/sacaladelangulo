package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.math.BigDecimal;
import java.util.List;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;

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
        BigDecimal precioBase,
        BigDecimal montoSena,
        List<TarifaDto> tarifas,
        List<Long> canchasFisicasIds,
        Integer cantidadCanchasNecesarias
) {
}
