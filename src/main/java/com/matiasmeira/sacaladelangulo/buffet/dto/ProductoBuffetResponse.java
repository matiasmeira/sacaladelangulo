package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;

public record ProductoBuffetResponse(
        Long id,
        String nombre,
        String descripcion,
        BigDecimal precio,
        Integer stock,
        Long establecimientoId
) {
}
