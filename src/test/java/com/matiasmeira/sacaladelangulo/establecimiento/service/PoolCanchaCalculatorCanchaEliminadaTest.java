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
 * Regresión que fija un hallazgo del análisis previo de CanchaEliminacionService: eliminar
 * (deletedAt) una física que ya está desactivada NO cambia en nada el cálculo de pool de
 * ninguna lógica que la use, porque {@link PoolCanchaCalculator#footprint} ya la excluye de
 * la capacidad del grupo por {@code isActive=false} -- un chequeo que no mira deletedAt en
 * absoluto. Como la Precondición 1 de CanchaEliminacionService exige que la cancha ya esté
 * inactiva antes de eliminarla, su aporte al footprint ya era cero desde que se desactivó, no
 * desde que se elimina: no hace falta (ni tiene efecto) que PoolCanchaCalculator sepa de
 * deletedAt.
 */
class PoolCanchaCalculatorCanchaEliminadaTest {

    private Cancha fisicaActiva(long id) {
        return Cancha.builder().id(id).nombre("F" + id).isActive(true)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ONE).build();
    }

    private Cancha fisicaDesactivada(long id) {
        return Cancha.builder().id(id).nombre("F" + id).isActive(false)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ONE).build();
    }

    private Cancha fisicaEliminada(long id) {
        return Cancha.builder().id(id).nombre("F" + id).isActive(false).deletedAt(LocalDateTime.now())
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ONE).build();
    }

    private Cancha logica(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder()
                .id(id).nombre(nombre).canchasFisicas(pool).canchasNecesarias(canchasNecesarias)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ONE).build();
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
    void fisicaEliminada_mismaCapacidadQueFisicaMeramenteDesactivada() {
        Cancha f1 = fisicaActiva(1), f2 = fisicaActiva(2);
        Cancha f3Desactivada = fisicaDesactivada(3);
        Cancha logicaConDesactivada = logica(9, "C9-desactivada", Set.of(f1, f2, f3Desactivada), 3);

        Cancha f3Eliminada = fisicaEliminada(3);
        Cancha logicaConEliminada = logica(9, "C9-eliminada", Set.of(f1, f2, f3Eliminada), 3);

        List<Cancha> todasConDesactivada = List.of(f1, f2, f3Desactivada, logicaConDesactivada);
        List<Cancha> todasConEliminada = List.of(f1, f2, f3Eliminada, logicaConEliminada);

        // Capacidad de grupo = 2 en ambos casos (f1 + f2; la física #3 no cuenta ni
        // desactivada ni eliminada): una reserva que ocupa exactamente esas 2 sigue dejando
        // sin cupo a una candidata adicional, sea la física #3 solo inactiva o ya eliminada.
        boolean disponibleConDesactivada = PoolCanchaCalculator.hayDisponibilidad(
                f1, List.of(reservaSobre(f2)), todasConDesactivada);
        boolean disponibleConEliminada = PoolCanchaCalculator.hayDisponibilidad(
                f1, List.of(reservaSobre(f2)), todasConEliminada);

        assertThat(disponibleConDesactivada).isEqualTo(disponibleConEliminada);
    }

    @Test
    void logicaConFisicaEliminada_capacidadDeGrupoExcluyeLaEliminada() {
        Cancha f1 = fisicaActiva(1), f2 = fisicaActiva(2), f3 = fisicaEliminada(3);
        Cancha logicaDeTres = logica(9, "C9", Set.of(f1, f2, f3), 2);
        List<Cancha> todas = List.of(f1, f2, f3, logicaDeTres);

        // canchasRelacionadas (footprint de C9) sólo debe incluir las físicas activas: f1 y f2.
        Set<Long> relacionadas = PoolCanchaCalculator.canchasRelacionadas(logicaDeTres, todas);

        assertThat(relacionadas).contains(1L, 2L).doesNotContain(3L);
    }
}
