package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Card de un complejo en el listado público (home o búsqueda). No incluye duenoId ni
 * ningún otro dato interno del dueño (ver contrato de zona pública).
 */
public record ComplejoCardResponse(
        String slug,
        String nombre,
        String direccion,
        String fotoPrincipal,
        Set<Deporte> deportes,
        BigDecimal precioDesde,
        Boolean requiereSena,
        BigDecimal senaDesde,
        Double distanciaKm,
        Double promedioCalificacion,
        Long cantidadCalificaciones
) {
}
