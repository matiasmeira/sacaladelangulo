package com.matiasmeira.sacaladelangulo.cierrecaja.repository;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    List<MovimientoCaja> findByTurnoCajaIdOrderByFechaHoraAsc(Long turnoCajaId);
}
