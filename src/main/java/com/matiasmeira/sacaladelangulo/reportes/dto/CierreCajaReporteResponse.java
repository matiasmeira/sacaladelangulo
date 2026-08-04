package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reporte de diferencias de caja de un establecimiento en un rango de fechas: los turnos
 * CERRADO del período con su diferencia individual, más el faltante y sobrante acumulados
 * (sumas separadas de diferencias negativas y positivas, ambas expresadas como valores
 * positivos) para detectar descuadres recurrentes.
 */
public record CierreCajaReporteResponse(
        RangoFechas periodo,
        List<TurnoCajaResumenDto> turnos,
        BigDecimal faltanteAcumulado,
        BigDecimal sobranteAcumulado
) {
}
