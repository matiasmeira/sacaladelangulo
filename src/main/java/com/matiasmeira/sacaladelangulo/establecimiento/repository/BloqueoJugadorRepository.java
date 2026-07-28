package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BloqueoJugadorRepository extends JpaRepository<BloqueoJugador, Long> {

    boolean existsByEstablecimientoIdAndJugadorId(Long establecimientoId, Long jugadorId);

    Optional<BloqueoJugador> findByEstablecimientoIdAndJugadorId(Long establecimientoId, Long jugadorId);

    List<BloqueoJugador> findByEstablecimientoIdOrderByFechaBloqueoDesc(Long establecimientoId);
}
