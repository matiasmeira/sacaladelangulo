package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIAGNÓSTICO (no fix): reproduce el bug de disponibilidad entre dos canchas lógicas que
 * comparten canchas físicas pero donde ninguna es "pool de la otra". Ver hilo de
 * investigación sobre PoolCanchaCalculator.hayDisponibilidad — canchasRelacionadas tiene
 * el mismo agujero (test simétrico más abajo).
 *
 * Escenario: F1, F2, F3 físicas de 5. Lógica9 = pool [F1,F2,F3] (canchasNecesarias=3).
 * Lógica7 = pool [F1,F2] (canchasNecesarias=2). Reservar una debería bloquear la otra
 * porque comparten F1/F2, pero afectaEstePool solo compara lógica↔física, nunca
 * lógica↔lógica.
 */
class PoolCanchaCalculatorPoolsSuperpuestosTest {

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

    @Test
    void reservarLogicaDe9NoDejaDisponibleLaLogicaDe7QueComparteFisicas() {
        Cancha f1 = fisica(1);
        Cancha f2 = fisica(2);
        Cancha f3 = fisica(3);
        Cancha logica9 = logica(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        Cancha logica7 = logica(7, "Cancha de 7", Set.of(f1, f2), 2);
        List<Cancha> todasLasCanchas = List.of(f1, f2, f3, logica9, logica7);

        List<Reserva> solapadas = List.of(reservaSobre(logica9));

        boolean hayDisponibilidadParaLogica7 = PoolCanchaCalculator.hayDisponibilidad(logica7, solapadas, todasLasCanchas);

        // BUG: hoy da true. F1 y F2 ya están comprometidas por la reserva de logica9,
        // así que logica7 (que necesita F1+F2) NO debería tener disponibilidad.
        assertThat(hayDisponibilidadParaLogica7).isFalse();
    }

    @Test
    void reservarLogicaDe7NoDejaDisponibleLaLogicaDe9QueComparteFisicas() {
        Cancha f1 = fisica(1);
        Cancha f2 = fisica(2);
        Cancha f3 = fisica(3);
        Cancha logica9 = logica(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        Cancha logica7 = logica(7, "Cancha de 7", Set.of(f1, f2), 2);
        List<Cancha> todasLasCanchas = List.of(f1, f2, f3, logica9, logica7);

        List<Reserva> solapadas = List.of(reservaSobre(logica7));

        boolean hayDisponibilidadParaLogica9 = PoolCanchaCalculator.hayDisponibilidad(logica9, solapadas, todasLasCanchas);

        // BUG (caso simétrico): hoy da true. F1 y F2 ya están comprometidas por logica7,
        // logica9 necesita F1+F2+F3, no puede estar disponible.
        assertThat(hayDisponibilidadParaLogica9).isFalse();
    }

    @Test
    void canchasRelacionadasNoIncluyeALaOtraLogicaQueComparteFisicas() {
        Cancha f1 = fisica(1);
        Cancha f2 = fisica(2);
        Cancha f3 = fisica(3);
        Cancha logica9 = logica(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        Cancha logica7 = logica(7, "Cancha de 7", Set.of(f1, f2), 2);
        List<Cancha> todasLasCanchas = List.of(f1, f2, f3, logica9, logica7);

        Set<Long> relacionadasDeLogica7 = PoolCanchaCalculator.canchasRelacionadas(logica7, todasLasCanchas);

        // BUG: el lock pesimista (bloquearCanchasRelacionadas) no toma el id de logica9,
        // pese a que logica9 consume F1/F2, que también son necesarias para logica7.
        // Dos transacciones concurrentes reservando logica7 y logica9 no se serializan entre sí.
        assertThat(relacionadasDeLogica7).contains(9L);
    }
}
