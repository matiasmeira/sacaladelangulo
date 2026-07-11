package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.TarifaDto;

/**
 * DTO de respuesta para una cancha.
 */
public record CanchaResponse(
        Long id,
        String nombre,
        Set<Deporte> deportes,
        Integer capacidad,
        Boolean isActive,
        Long establecimientoId,
        BigDecimal precioBase,
        BigDecimal montoSena,
        List<Integer> duracionesPermitidas,
        Boolean permiteInicioMediaHora,
        List<TarifaDto> tarifas,
        List<Long> canchasFisicasIds,
        Integer cantidadCanchasNecesarias
) {
}
