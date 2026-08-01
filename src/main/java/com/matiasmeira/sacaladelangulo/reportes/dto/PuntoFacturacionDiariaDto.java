package com.matiasmeira.sacaladelangulo.reportes.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Un punto de la serie temporal, alineando el día N del período actual con el día N del
 * período anterior (ambos rangos tienen igual duración por diseño).
 */
public record PuntoFacturacionDiariaDto(
        int diaIndice,
        LocalDate fechaActual,
        BigDecimal montoActual,
        LocalDate fechaAnterior,
        BigDecimal montoAnterior
) {
}
