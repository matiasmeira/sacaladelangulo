package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Cálculo puro (sin acceso a base de datos) del precio final de una reserva.
 * Resuelve primero qué fuente de precio aplica -la Tarifa que matchea día/franja horaria
 * de fechaHoraInicio, o si ninguna matchea, la Cancha misma- y, dentro de esa fuente,
 * prioriza un precio exacto configurado para la duración pedida (Cancha/Tarifa
 * .preciosPorDuracion) por sobre el cálculo proporcional histórico (precioPorHora ×
 * horas). Toda cancha/tarifa sin precios por duración configurados (el caso de todas las
 * existentes al momento de introducir esta clase) cae exactamente en el proporcional de
 * siempre, sin cambio de comportamiento.
 */
public final class PrecioReservaCalculator {

    private PrecioReservaCalculator() {
    }

    public static BigDecimal calcularPrecio(Cancha cancha, LocalDateTime fechaHoraInicio, long duracionMinutos) {
        Tarifa tarifaAplicable = cancha.getTarifas().stream()
                .filter(tarifa -> matchea(tarifa, fechaHoraInicio))
                .findFirst()
                .orElse(null);

        Map<Integer, BigDecimal> preciosPorDuracion = tarifaAplicable != null
                ? tarifaAplicable.getPreciosPorDuracion()
                : cancha.getPreciosPorDuracion();

        BigDecimal precioExacto = preciosPorDuracion.get((int) duracionMinutos);
        if (precioExacto != null) {
            return precioExacto;
        }

        BigDecimal precioPorHora = tarifaAplicable != null ? tarifaAplicable.getPrecio() : cancha.getPrecioBase();
        BigDecimal duracionHoras = BigDecimal.valueOf(duracionMinutos)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        return precioPorHora.multiply(duracionHoras);
    }

    private static boolean matchea(Tarifa tarifa, LocalDateTime fechaHoraInicio) {
        return tarifa.getDiaSemana() == fechaHoraInicio.getDayOfWeek()
                && !fechaHoraInicio.toLocalTime().isBefore(tarifa.getHoraInicio())
                && fechaHoraInicio.toLocalTime().isBefore(tarifa.getHoraFin());
    }
}
