package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("PrecioReservaCalculator")
class PrecioReservaCalculatorTest {

    /**
     * Fechas calculadas relativas a "hoy" (en vez de hardcodeadas) para no depender de
     * asumir a mano qué día de la semana cae una fecha fija.
     */
    private static final LocalDateTime MARTES_10HS = proximoDia(DayOfWeek.TUESDAY).atTime(10, 0);
    private static final LocalDateTime SABADO_20HS = proximoDia(DayOfWeek.SATURDAY).atTime(20, 0);

    private static LocalDate proximoDia(DayOfWeek diaSemana) {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != diaSemana) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    private Cancha canchaConPrecioBase(BigDecimal precioBase) {
        return Cancha.builder()
                .id(1L)
                .precioBase(precioBase)
                .tarifas(new ArrayList<>())
                .preciosPorDuracion(new HashMap<>())
                .build();
    }

    @Test
    @DisplayName("Sin preciosPorDuracion configurado, calcula proporcional (regresión del comportamiento actual)")
    void sinPreciosPorDuracion_CalculaProporcional() {
        Cancha cancha = canchaConPrecioBase(BigDecimal.valueOf(10000));

        assertEquals(0, BigDecimal.valueOf(10000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 60)));
        assertEquals(0, BigDecimal.valueOf(15000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 90)));
        assertEquals(0, BigDecimal.valueOf(20000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 120)));
    }

    @Test
    @DisplayName("Con precio exacto solo para 120 min, 60/90 siguen proporcionales y 120 usa el valor exacto")
    void configuracionParcial_SoloAlgunaDuracionUsaPrecioExacto() {
        Map<Integer, BigDecimal> precios = new HashMap<>();
        precios.put(120, BigDecimal.valueOf(18000)); // más barato que el proporcional (20000)

        Cancha cancha = canchaConPrecioBase(BigDecimal.valueOf(10000));
        cancha.setPreciosPorDuracion(precios);

        assertEquals(0, BigDecimal.valueOf(10000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 60)));
        assertEquals(0, BigDecimal.valueOf(15000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 90)));
        assertEquals(0, BigDecimal.valueOf(18000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 120)));
    }

    @Test
    @DisplayName("Si hay una Tarifa que matchea el horario, su preciosPorDuracion tiene prioridad sobre el de la Cancha")
    void tarifaAplicable_UsaPreciosPorDuracionPropios() {
        Map<Integer, BigDecimal> preciosCancha = new HashMap<>();
        preciosCancha.put(120, BigDecimal.valueOf(18000));

        Map<Integer, BigDecimal> preciosTarifaFinde = new HashMap<>();
        preciosTarifaFinde.put(120, BigDecimal.valueOf(30000));

        Tarifa tarifaFinde = Tarifa.builder()
                .diaSemana(DayOfWeek.SATURDAY)
                .horaInicio(LocalTime.of(18, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(20000))
                .preciosPorDuracion(preciosTarifaFinde)
                .build();

        Cancha cancha = canchaConPrecioBase(BigDecimal.valueOf(10000));
        cancha.setPreciosPorDuracion(preciosCancha);
        cancha.setTarifas(List.of(tarifaFinde));

        // Sábado 20hs: matchea la Tarifa -> usa su precio exacto (30000), no el de la Cancha
        assertEquals(0, BigDecimal.valueOf(30000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, SABADO_20HS, 120)));

        // Martes 10hs: no matchea ninguna Tarifa -> usa el precio exacto de la Cancha (18000)
        assertEquals(0, BigDecimal.valueOf(18000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 120)));
    }

    @Test
    @DisplayName("Tarifa aplicable sin precio exacto para la duración pedida cae proporcional sobre su propio precio/hora")
    void tarifaAplicable_SinPrecioExacto_CaeProporcionalSobrePrecioDeLaTarifa() {
        Tarifa tarifaFinde = Tarifa.builder()
                .diaSemana(DayOfWeek.SATURDAY)
                .horaInicio(LocalTime.of(18, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(20000))
                .preciosPorDuracion(new HashMap<>())
                .build();

        Cancha cancha = canchaConPrecioBase(BigDecimal.valueOf(10000));
        cancha.setTarifas(List.of(tarifaFinde));

        assertEquals(0, BigDecimal.valueOf(40000).compareTo(
                PrecioReservaCalculator.calcularPrecio(cancha, SABADO_20HS, 120)));
    }
}
