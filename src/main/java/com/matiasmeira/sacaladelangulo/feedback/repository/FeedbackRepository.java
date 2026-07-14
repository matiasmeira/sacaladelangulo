package com.matiasmeira.sacaladelangulo.feedback.repository;

import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repositorio para la entidad Feedback. No hay FK directa a Establecimiento (ver
 * Feedback), por lo que todas las consultas por establecimiento navegan
 * f.reserva.cancha.establecimiento, igual que ReservaRepository con Reserva.
 */
@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    boolean existsByReservaId(Long reservaId);

    @Query("SELECT f FROM Feedback f JOIN FETCH f.reserva r JOIN FETCH r.cancha c JOIN FETCH c.establecimiento " +
           "LEFT JOIN FETCH r.jugador " +
           "WHERE c.establecimiento.id = :estId ORDER BY f.fechaCreacion DESC")
    Page<Feedback> findByEstablecimientoId(@Param("estId") Long estId, Pageable pageable);

    @Query("SELECT f FROM Feedback f JOIN f.reserva r JOIN r.cancha c " +
           "WHERE c.establecimiento.id = :estId AND f.destacado = true")
    Optional<Feedback> findDestacadoByEstablecimientoId(@Param("estId") Long estId);

    @Query("SELECT AVG(f.puntuacion) FROM Feedback f JOIN f.reserva r JOIN r.cancha c " +
           "WHERE c.establecimiento.id = :estId")
    Double calcularPromedioByEstablecimientoId(@Param("estId") Long estId);

    @Query("SELECT COUNT(f) FROM Feedback f JOIN f.reserva r JOIN r.cancha c " +
           "WHERE c.establecimiento.id = :estId")
    Long contarByEstablecimientoId(@Param("estId") Long estId);

    /**
     * Variantes en lote (agrupadas por establecimiento) usadas por
     * EstablecimientoService.buscarEstablecimientos/obtenerMisEstablecimientos para no
     * ejecutar una consulta de promedio/destacado por cada establecimiento del listado.
     */
    @Query("SELECT c.establecimiento.id, AVG(f.puntuacion) FROM Feedback f JOIN f.reserva r JOIN r.cancha c " +
           "WHERE c.establecimiento.id IN :estIds GROUP BY c.establecimiento.id")
    List<Object[]> calcularPromediosPorEstablecimientos(@Param("estIds") List<Long> estIds);

    @Query("SELECT c.establecimiento.id, COUNT(f) FROM Feedback f JOIN f.reserva r JOIN r.cancha c " +
           "WHERE c.establecimiento.id IN :estIds GROUP BY c.establecimiento.id")
    List<Object[]> contarPorEstablecimientos(@Param("estIds") List<Long> estIds);

    @Query("SELECT f FROM Feedback f JOIN FETCH f.reserva r JOIN FETCH r.cancha c " +
           "LEFT JOIN FETCH r.jugador " +
           "WHERE c.establecimiento.id IN :estIds AND f.destacado = true")
    List<Feedback> findDestacadosByEstablecimientoIdIn(@Param("estIds") List<Long> estIds);
}
