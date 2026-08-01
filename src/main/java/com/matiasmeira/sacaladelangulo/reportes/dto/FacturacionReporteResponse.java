package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

public record FacturacionReporteResponse(
        RangoFechas periodoActual,
        RangoFechas periodoAnterior,
        Comparativo<BigDecimal> totalFacturado,
        List<DesglosePorMetodoPagoDto> desglosePorMetodoPago,
        List<PuntoFacturacionDiariaDto> serieTemporal
) {
}
