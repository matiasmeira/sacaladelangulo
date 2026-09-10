package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoolCanchaCalculator.hayDisponibilidad razona por GRUPO de físicas (cierre transitivo de
 * pools que se intersectan), no por pool individual: dos canchas lógicas que comparten
 * físicas (aunque ninguna sea "pool de la otra") caen en el mismo grupo y compiten por la
 * misma capacidad. La asignación dentro de un grupo sigue siendo flexible (suma de
 * demandas vs. capacidad del grupo, sin importar qué físicas exactas se usan) — eso es
 * lo que permite el caso 2 (no bloquear de más).
 */
class PoolCanchaCalculatorGrupoFisicasTest {

    private Cancha fisica(long id) {
        return Cancha.builder().id(id).nombre("F" + id).precioBase(BigDecimal.TEN).montoSena(BigDecimal.ONE).build();
    }

    private Cancha logica(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder()
                .id(id)
                .nombre(nombre)
                .canchasFisicas(pool)
                .canchasNecesarias(canchasNecesarias)
                .precioBase(BigDecimal.TEN)
                .montoSena(BigDecimal.ONE)
                .build();
    }

    private Reserva reservaSobre(Cancha cancha) {
        return Reserva.builder()
                .cancha(cancha)
                .estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(LocalDateTime.of(2026, 9, 10, 20, 0))
                .fechaHoraFin(LocalDateTime.of(2026, 9, 10, 21, 0))
                .precioTotal(BigDecimal.TEN)
                .senaPagada(BigDecimal.ZERO)
                .build();
    }

    // ---- Caso 1: dos lógicas con el MISMO pool completo ----

    @Test
    void reservarC9_dejaC7NoDisponible_mismoPoolCompleto() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logica(9, "C9", Set.of(f1, f2, f3), 3);
        Cancha c7 = logica(7, "C7", Set.of(f1, f2, f3), 2);
        List<Cancha> todas = List.of(f1, f2, f3, c9, c7);

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(c7, List.of(reservaSobre(c9)), todas);

        assertThat(disponible).isFalse();
    }

    @Test
    void reservarC7_dejaC9NoDisponible_simetrico() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logica(9, "C9", Set.of(f1, f2, f3), 3);
        Cancha c7 = logica(7, "C7", Set.of(f1, f2, f3), 2);
        List<Cancha> todas = List.of(f1, f2, f3, c9, c7);

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(c9, List.of(reservaSobre(c7)), todas);

        assertThat(disponible).isFalse();
    }

    // ---- Caso 2: la asignación sigue siendo flexible dentro del grupo ----

    @Test
    void conC7Reservada_dosDeTres_fisicaSueltaSiDisponible() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logica(9, "C9", Set.of(f1, f2, f3), 3);
        Cancha c7 = logica(7, "C7", Set.of(f1, f2, f3), 2);
        List<Cancha> todas = List.of(f1, f2, f3, c9, c7);

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(f1, List.of(reservaSobre(c7)), todas);

        assertThat(disponible).isTrue();
    }

    // ---- Caso 3: agotar la capacidad del grupo con físicas sueltas ----

    @Test
    void conC7YUnaFisicaYaReservadas_segundaFisicaNoDisponible() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logica(9, "C9", Set.of(f1, f2, f3), 3);
        Cancha c7 = logica(7, "C7", Set.of(f1, f2, f3), 2);
        List<Cancha> todas = List.of(f1, f2, f3, c9, c7);

        List<Reserva> solapadas = List.of(reservaSobre(c7), reservaSobre(f1));

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(f2, solapadas, todas);

        assertThat(disponible).isFalse();
    }

    // ---- Caso 4: dos lógicas con pool idéntico (no una subconjunto de la otra) ----

    @Test
    void dosDe7ConPoolIdentico_reservadaLaPrimera_segundaSiDisponible() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3), f4 = fisica(4);
        Set<Cancha> pool = Set.of(f1, f2, f3, f4);
        Cancha d7a = logica(71, "D7-A", pool, 2);
        Cancha d7b = logica(72, "D7-B", pool, 2);
        List<Cancha> todas = List.of(f1, f2, f3, f4, d7a, d7b);

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(d7b, List.of(reservaSobre(d7a)), todas);

        assertThat(disponible).isTrue();
    }

    @Test
    void dosDe7ConPoolIdentico_reservadasAmbas_fisicaSueltaNoDisponible() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3), f4 = fisica(4);
        Set<Cancha> pool = Set.of(f1, f2, f3, f4);
        Cancha d7a = logica(71, "D7-A", pool, 2);
        Cancha d7b = logica(72, "D7-B", pool, 2);
        List<Cancha> todas = List.of(f1, f2, f3, f4, d7a, d7b);

        List<Reserva> solapadas = List.of(reservaSobre(d7a), reservaSobre(d7b));

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(f1, solapadas, todas);

        assertThat(disponible).isFalse();
    }

    @Test
    void dosDe7ConPoolIdentico_unaDe7YDosFisicasReservadas_otraDe7NoDisponible() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3), f4 = fisica(4);
        Set<Cancha> pool = Set.of(f1, f2, f3, f4);
        Cancha d7a = logica(71, "D7-A", pool, 2);
        Cancha d7b = logica(72, "D7-B", pool, 2);
        List<Cancha> todas = List.of(f1, f2, f3, f4, d7a, d7b);

        List<Reserva> solapadas = List.of(reservaSobre(d7a), reservaSobre(f3), reservaSobre(f4));

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(d7b, solapadas, todas);

        assertThat(disponible).isFalse();
    }

    // ---- Caso 5: grupos disjuntos no se pisan ----

    @Test
    void dosGruposDisjuntosDeFisicas_reservarEnUnoNoAfectaAlOtro() {
        Cancha f1 = fisica(1), f2 = fisica(2);
        Cancha f3 = fisica(3), f4 = fisica(4);
        Cancha grupoA = logica(50, "Grupo A", Set.of(f1, f2), 2);
        Cancha grupoB = logica(60, "Grupo B", Set.of(f3, f4), 2);
        List<Cancha> todas = List.of(f1, f2, f3, f4, grupoA, grupoB);

        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(grupoB, List.of(reservaSobre(grupoA)), todas);

        assertThat(disponible).isTrue();
    }

    // ---- Caso 6: sin ninguna lógica, comportamiento idéntico al actual (nunca restringe) ----

    @Test
    void sinNingunaLogicaEnElEstablecimiento_nuncaRestringePorPool() {
        Cancha f1 = fisica(1), f2 = fisica(2);
        List<Cancha> todas = new ArrayList<>(List.of(f1, f2));

        // Ninguna cancha define un pool: hayDisponibilidad no debe evaluar nada por
        // "pool" (esa colisión exacta la resuelve validarCanchaExactaLibre, no esta clase).
        boolean disponible = PoolCanchaCalculator.hayDisponibilidad(f1, List.of(reservaSobre(f1)), todas);

        assertThat(disponible).isTrue();
    }
}
