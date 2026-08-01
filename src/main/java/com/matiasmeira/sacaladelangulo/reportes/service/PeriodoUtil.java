package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Calcula el período inmediatamente anterior de igual duración a [desde, hasta], para las
 * comparaciones que piden todos los endpoints de reportes.
 */
public final class PeriodoUtil {

    private PeriodoUtil() {
    }

    public static RangoFechas periodoAnterior(LocalDate desde, LocalDate hasta) {
        long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        LocalDate haAnterior = desde.minusDays(1);
        return new RangoFechas(haAnterior.minusDays(dias - 1), haAnterior);
    }

    public static LocalDateTime inicioDelDia(LocalDate fecha) {
        return fecha.atStartOfDay();
    }

    public static LocalDateTime finDelDia(LocalDate fecha) {
        return fecha.atTime(LocalTime.MAX);
    }
}
