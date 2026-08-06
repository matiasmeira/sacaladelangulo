package com.matiasmeira.sacaladelangulo.cierrecaja.repository;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MovimientoCajaRepository extends JpaRepository<MovimientoCaja, Long> {

    List<MovimientoCaja> findByTurnoCajaIdOrderByFechaHoraAsc(Long turnoCajaId);

    /**
     * Último movimiento (por fecha) registrado para una entidad de origen (venta/gasto/reserva)
     * puntual, sin importar en qué turno haya quedado. Se usa para saber si todavía "vive" en
     * el turno actualmente ABIERTO antes de compensarlo (ver
     * TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto).
     */
    Optional<MovimientoCaja> findTopByOrigenAndReferenciaIdOrderByFechaHoraDesc(OrigenMovimientoCaja origen, Long referenciaId);
}
