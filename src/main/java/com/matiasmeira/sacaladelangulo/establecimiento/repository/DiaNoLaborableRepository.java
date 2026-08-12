package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DiaNoLaborableRepository extends JpaRepository<DiaNoLaborable, Long> {

    boolean existsByEstablecimientoIdAndFecha(Long establecimientoId, LocalDate fecha);

    Optional<DiaNoLaborable> findByEstablecimientoIdAndFecha(Long establecimientoId, LocalDate fecha);

    List<DiaNoLaborable> findByEstablecimientoIdOrderByFechaAsc(Long establecimientoId);

    /**
     * Variante en lote para la grilla de disponibilidad: trae todos los días no
     * laborables del rango consultado en una sola consulta, en vez de una por día.
     */
    List<DiaNoLaborable> findByEstablecimientoIdAndFechaBetween(Long establecimientoId, LocalDate desde, LocalDate hasta);

    /**
     * Variante en lote para el filtro de disponibilidad de la búsqueda pública (ver
     * ComplejoPublicoService.filtrarPorDisponibilidad): consulta todos los complejos
     * candidatos de una sola vez para una fecha puntual, en vez de una consulta por
     * complejo.
     */
    List<DiaNoLaborable> findByEstablecimientoIdInAndFecha(List<Long> establecimientoIds, LocalDate fecha);
}
