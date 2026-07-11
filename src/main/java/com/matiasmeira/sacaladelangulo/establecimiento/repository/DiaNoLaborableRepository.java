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
}
