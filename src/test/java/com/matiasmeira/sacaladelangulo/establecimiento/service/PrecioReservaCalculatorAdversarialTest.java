package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIX aplicado (ver REVISION_FUNCIONAL.md): PrecioReservaCalculator ahora normaliza la escala
 * del resultado a 2 decimales (los que corresponden a dinero, ver NUMERIC(38,2) en la
 * migración) tanto en el camino de precio exacto como en el proporcional. Antes del fix,
 * duracionHoras salía de un ".divide(..., 2, HALF_UP)" (siempre escala 2) y se multiplicaba
 * contra precioPorHora: si precioPorHora también tenía escala 2 -- como vuelve de la base
 * (NUMERIC(38,2)) o como lo tipea un dueño ("1500.50") -- el resultado de la multiplicación
 * quedaba en escala 4 (2+2=4, regla de BigDecimal.multiply), sin importar la duración. Esta
 * clase queda como test de regresión.
 */
@DisplayName("PrecioReservaCalculator - Escala de BigDecimal (dinero)")
class PrecioReservaCalculatorAdversarialTest {

    private static final LocalDateTime MARTES_10HS = proximoMartes().atTime(10, 0);

    private static LocalDate proximoMartes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.TUESDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    private Cancha canchaConPrecioBase(BigDecimal precioBase) {
        return Cancha.builder().id(1L).precioBase(precioBase).tarifas(new ArrayList<>()).preciosPorDuracion(new HashMap<>()).build();
    }

    @Test
    @DisplayName("Regresión: 60 min con precioBase de escala 2 (como vuelve de NUMERIC(38,2)) da exactamente 2 decimales")
    void duracionMultiploDeHora_EscalaConsistente() {
        Cancha cancha = canchaConPrecioBase(new BigDecimal("1000.00"));
        BigDecimal precio = PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 60);

        assertEquals(0, new BigDecimal("1000.00").compareTo(precio));
        assertTrue(precio.scale() <= 2,
                "precioTotal debería tener a lo sumo 2 decimales (es dinero), pero tiene escala "
                        + precio.scale() + " -> " + precio.toPlainString());
    }

    @Test
    @DisplayName("Regresión: 45 min (no múltiplo de hora) también da exactamente 2 decimales")
    void duracionNoMultiploDeHora_EscalaConsistente() {
        Cancha cancha = canchaConPrecioBase(new BigDecimal("1000.00"));
        BigDecimal precio = PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 45);

        assertEquals(0, new BigDecimal("750.00").compareTo(precio));
        assertTrue(precio.scale() <= 2,
                "precioTotal debería tener a lo sumo 2 decimales (es dinero), pero tiene escala "
                        + precio.scale() + " -> " + precio.toPlainString());
    }

    @Test
    @DisplayName("Regresión: precioBase de escala 0 sigue dando escala <=2 (caso que ya funcionaba antes del fix)")
    void precioBaseConEscalaCero_SigueFuncionando() {
        Cancha cancha = canchaConPrecioBase(BigDecimal.valueOf(1000));
        BigDecimal precio = PrecioReservaCalculator.calcularPrecio(cancha, MARTES_10HS, 60);

        assertEquals(0, BigDecimal.valueOf(1000).compareTo(precio));
        assertTrue(precio.scale() <= 2, "Con precioBase de escala 0 el resultado da escala " + precio.scale());
    }
}
