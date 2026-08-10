package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.util.Set;

public record CanchaPublicaDto(
        Long id,
        String nombre,
        Set<Deporte> deportes,
        BigDecimal precioDesde
) {
}
