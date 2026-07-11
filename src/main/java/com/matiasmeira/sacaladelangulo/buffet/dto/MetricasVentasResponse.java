package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Métricas de ventas del buffet de un establecimiento en un rango de fechas.
 * Solo considera ventas CONFIRMADA (las canceladas no suman ingresos ni unidades).
 */
public record MetricasVentasResponse(
        Long establecimientoId,
        LocalDate desde,
        LocalDate hasta,
        BigDecimal ingresoTotal,
        Long cantidadVentas,
        BigDecimal ticketPromedio,
        List<ProductoMasVendidoResponse> productosMasVendidos
) {
}
