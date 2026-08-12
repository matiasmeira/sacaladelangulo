package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HorarioAtencionCalculator - Resolución de ventana horaria por fecha")
class HorarioAtencionCalculatorTest {

    @Test
    @DisplayName("calcularVentana_HorarioMismoDia_DevuelveInicioYFinEnLaMismaFecha")
    void calcularVentana_HorarioMismoDia_DevuelveInicioYFinEnLaMismaFecha() {
        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build();
        LocalDate fecha = LocalDate.of(2026, 8, 10);

        HorarioAtencionCalculator.VentanaHoraria ventana = HorarioAtencionCalculator.calcularVentana(horario, fecha);

        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), ventana.inicio());
        assertEquals(LocalDateTime.of(2026, 8, 10, 23, 0), ventana.fin());
    }

    @Test
    @DisplayName("calcularVentana_HorarioCruzaMedianoche_FinCaeEnElDiaSiguiente")
    void calcularVentana_HorarioCruzaMedianoche_FinCaeEnElDiaSiguiente() {
        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(20, 0))
                .horaCierre(LocalTime.of(2, 0))
                .build();
        LocalDate fecha = LocalDate.of(2026, 8, 10);

        HorarioAtencionCalculator.VentanaHoraria ventana = HorarioAtencionCalculator.calcularVentana(horario, fecha);

        assertEquals(LocalDateTime.of(2026, 8, 10, 20, 0), ventana.inicio());
        assertEquals(LocalDateTime.of(2026, 8, 11, 2, 0), ventana.fin());
    }
}
