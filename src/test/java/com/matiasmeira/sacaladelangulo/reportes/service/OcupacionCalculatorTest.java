package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.reportes.dto.FranjaHoraria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Escenario de un solo día conocido, calculado a mano:
 * - Lunes, horario de atención 08:00-22:00 (14h), 1 cancha activa.
 * - 1 bloqueo de mantenimiento 10:00-12:00 (2h), completamente dentro de la franja mañana.
 * - 3 reservas FINALIZADA: 09:00-10:00 (mañana), 12:30-13:30 (cruza mañana/tarde),
 *   20:00-21:00 (noche).
 */
@DisplayName("OcupacionCalculator")
class OcupacionCalculatorTest {

    private static LocalDate proximoLunes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.MONDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @Test
    @DisplayName("Calcula horas disponibles/reservadas totales, por franja y por cancha con datos conocidos")
    void calcularConDatosConocidos() {
        LocalDate lunes = proximoLunes();

        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(8, 0))
                .horaCierre(LocalTime.of(22, 0))
                .build();

        OcupacionCalculator.CanchaInfo cancha = new OcupacionCalculator.CanchaInfo(1L, "Cancha 1");

        OcupacionCalculator.BloqueoProyeccion bloqueo = new OcupacionCalculator.BloqueoProyeccion(
                1L, lunes.atTime(10, 0), lunes.atTime(12, 0));

        List<OcupacionCalculator.ReservaProyeccion> reservas = List.of(
                new OcupacionCalculator.ReservaProyeccion(1L, lunes.atTime(9, 0), lunes.atTime(10, 0)),
                new OcupacionCalculator.ReservaProyeccion(1L, lunes.atTime(12, 30), lunes.atTime(13, 30)),
                new OcupacionCalculator.ReservaProyeccion(1L, lunes.atTime(20, 0), lunes.atTime(21, 0))
        );

        OcupacionCalculator.Resultado resultado = OcupacionCalculator.calcular(
                lunes, lunes,
                List.of(horario),
                Set.of(),
                List.of(cancha),
                List.of(bloqueo),
                reservas
        );

        assertEquals(0, BigDecimal.valueOf(12.00).compareTo(resultado.horasDisponibles()));
        assertEquals(0, BigDecimal.valueOf(3.00).compareTo(resultado.horasReservadas()));

        var manana = resultado.porFranja().get(FranjaHoraria.MANANA);
        assertEquals(0, BigDecimal.valueOf(3.00).compareTo(manana.disponibles()));
        assertEquals(0, BigDecimal.valueOf(1.50).compareTo(manana.reservadas()));

        var tarde = resultado.porFranja().get(FranjaHoraria.TARDE);
        assertEquals(0, BigDecimal.valueOf(6.00).compareTo(tarde.disponibles()));
        assertEquals(0, BigDecimal.valueOf(0.50).compareTo(tarde.reservadas()));

        var noche = resultado.porFranja().get(FranjaHoraria.NOCHE);
        assertEquals(0, BigDecimal.valueOf(3.00).compareTo(noche.disponibles()));
        assertEquals(0, BigDecimal.valueOf(1.00).compareTo(noche.reservadas()));

        var porCancha = resultado.porCancha().get(1L);
        assertEquals(0, BigDecimal.valueOf(12.00).compareTo(porCancha.disponibles()));
        assertEquals(0, BigDecimal.valueOf(3.00).compareTo(porCancha.reservadas()));
    }

    @Test
    @DisplayName("Un día no laborable no aporta horas disponibles, aunque tenga horario de atención ese día de semana")
    void diaNoLaborable_NoAportaHorasDisponibles() {
        LocalDate lunes = proximoLunes();
        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(8, 0))
                .horaCierre(LocalTime.of(22, 0))
                .build();
        OcupacionCalculator.CanchaInfo cancha = new OcupacionCalculator.CanchaInfo(1L, "Cancha 1");

        OcupacionCalculator.Resultado resultado = OcupacionCalculator.calcular(
                lunes, lunes,
                List.of(horario),
                Set.of(lunes),
                List.of(cancha),
                List.of(),
                List.of()
        );

        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(resultado.horasDisponibles()));
    }

    @Test
    @DisplayName("Sin horario de atención configurado para ese día, no hay horas disponibles")
    void sinHorarioParaEseDia_NoAportaHorasDisponibles() {
        LocalDate lunes = proximoLunes();
        HorarioAtencion horarioMartes = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.TUESDAY)
                .horaApertura(LocalTime.of(8, 0))
                .horaCierre(LocalTime.of(22, 0))
                .build();
        OcupacionCalculator.CanchaInfo cancha = new OcupacionCalculator.CanchaInfo(1L, "Cancha 1");

        OcupacionCalculator.Resultado resultado = OcupacionCalculator.calcular(
                lunes, lunes,
                List.of(horarioMartes),
                Set.of(),
                List.of(cancha),
                List.of(),
                List.of()
        );

        assertEquals(0, BigDecimal.ZERO.setScale(2).compareTo(resultado.horasDisponibles()));
    }
}
