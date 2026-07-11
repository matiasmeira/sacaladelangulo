package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;

public record ProductoMasVendidoResponse(
        Long productoId,
        String productoNombre,
        Long cantidadVendida,
        BigDecimal ingresoGenerado
) {
}
