package com.matiasmeira.sacaladelangulo.cierrecaja.repository;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TurnoCajaRepository extends JpaRepository<TurnoCaja, Long> {

    Optional<TurnoCaja> findByEstablecimientoIdAndEstado(Long establecimientoId, EstadoTurnoCaja estado);

    Optional<TurnoCaja> findByIdAndEstablecimientoId(Long id, Long establecimientoId);

    Page<TurnoCaja> findByEstablecimientoIdOrderByFechaAperturaDesc(Long establecimientoId, Pageable pageable);

    List<TurnoCaja> findByEstablecimientoIdAndEstadoAndFechaCierreBetween(
            Long establecimientoId, EstadoTurnoCaja estado, LocalDateTime desde, LocalDateTime hasta);
}
