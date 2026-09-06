package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TurnoFijoRepository extends JpaRepository<TurnoFijo, Long> {

    /**
     * Trae la regla con cancha -> establecimiento -> dueño en una sola consulta: todas las
     * operaciones de escritura arrancan validando contra el establecimiento, y las tres
     * asociaciones son LAZY.
     */
    @Query("SELECT t FROM TurnoFijo t " +
           "LEFT JOIN FETCH t.jugador " +
           "JOIN FETCH t.cancha c " +
           "JOIN FETCH c.establecimiento e " +
           "JOIN FETCH e.dueno " +
           "WHERE t.id = :id")
    Optional<TurnoFijo> findByIdConCanchaYEstablecimiento(@Param("id") Long id);

    /** @EntityGraph por el mismo motivo que los listados de ReservaRepository: evitar N+1. */
    @EntityGraph(attributePaths = {"jugador", "cancha"})
    Page<TurnoFijo> findByCancha_Establecimiento_IdAndEstado(Long estId, EstadoTurnoFijo estado, Pageable pageable);

    boolean existsByRenovadoDesdeId(Long renovadoDesdeId);
}
