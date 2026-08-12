package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cálculo puro (sin acceso a base de datos) de la ventana horaria concreta que resulta de
 * aplicar un HorarioAtencion a una fecha puntual. Maneja el caso de un horario que cierra
 * después de medianoche (cierre < apertura -> el cierre cae calendáricamente al día
 * siguiente). Extraído de DisponibilidadService para que ComplejoPublicoService (filtro de
 * disponibilidad en la búsqueda pública) también lo use, en vez de tener dos
 * implementaciones de este cálculo que puedan desincronizarse.
 */
public final class HorarioAtencionCalculator {

    private HorarioAtencionCalculator() {
    }

    public record VentanaHoraria(LocalDateTime inicio, LocalDateTime fin) {
    }

    public static VentanaHoraria calcularVentana(HorarioAtencion horario, LocalDate fecha) {
        LocalDateTime inicio = fecha.atTime(horario.getHoraApertura());
        LocalDateTime fin = horario.getHoraCierre().isBefore(horario.getHoraApertura())
                ? fecha.plusDays(1).atTime(horario.getHoraCierre())
                : fecha.atTime(horario.getHoraCierre());
        return new VentanaHoraria(inicio, fin);
    }
}
