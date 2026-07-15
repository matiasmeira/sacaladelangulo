package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repositorio para la entidad Reserva.
 * Proporciona operaciones CRUD y consultas especializadas para validación de solapamientos.
 */
@Repository
public interface ReservaRepository extends JpaRepository<Reserva, Long> {

    /**
     * Cuenta las reservas que se solapan con el período de tiempo especificado.
     * Excluye reservas canceladas para validar disponibilidad real.
     *
     * @param canchasIds Lista de IDs de canchas a validar
     * @param inicio Fecha y hora de inicio del período
     * @param fin Fecha y hora de fin del período
     * @return Cantidad de reservas solapadas
     */
    @Query("SELECT COUNT(r) FROM Reserva r " +
           "WHERE r.cancha.id IN :canchasIds " +
           "AND r.estado != 'CANCELADA' " +
           "AND (r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio)")
    long countReservasSolapadas(
            @Param("canchasIds") List<Long> canchasIds,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    /**
     * Obtiene todas las reservas solapadas de un predio (establecimiento).
     * Excluye reservas canceladas y trae las canchas asociadas.
     *
     * @param estId ID del establecimiento
     * @param inicio Fecha y hora de inicio del período
     * @param fin Fecha y hora de fin del período
     * @return Lista de reservas solapadas en el predio
     */
    @Query("SELECT r FROM Reserva r JOIN FETCH r.cancha c " +
           "WHERE c.establecimiento.id = :estId " +
           "AND r.estado != 'CANCELADA' " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findSuperpuestas(
            @Param("estId") Long estId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    org.springframework.data.domain.Page<Reserva> findByCanchaIdAndFechaHoraInicioBetweenAndEstadoNot(
            Long canchaId,
            LocalDateTime inicio,
            LocalDateTime fin,
            com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado,
            org.springframework.data.domain.Pageable pageable
    );

    @org.springframework.data.jpa.repository.Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId AND r.fechaHoraInicio < :finDia AND r.fechaHoraFin > :inicioDia AND r.estado != :estado")
    org.springframework.data.domain.Page<Reserva> findReservasEnRangoDiario(
            @org.springframework.data.repository.query.Param("canchaId") Long canchaId,
            @org.springframework.data.repository.query.Param("inicioDia") java.time.LocalDateTime inicioDia,
            @org.springframework.data.repository.query.Param("finDia") java.time.LocalDateTime finDia,
            @org.springframework.data.repository.query.Param("estado") com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId " +
           "AND r.estado != 'CANCELADA' " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findOverlappingByCanchaId(
            @Param("canchaId") Long canchaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    org.springframework.data.domain.Page<Reserva> findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNot(
            Long estId,
            LocalDateTime inicio,
            LocalDateTime fin,
            com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado,
            org.springframework.data.domain.Pageable pageable
    );

    org.springframework.data.domain.Page<Reserva> findByJugadorId(Long jugadorId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<Reserva> findByJugadorIdAndEstado(
            Long jugadorId,
            com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * IDs de canchas (dentro de la lista dada) que ya tienen alguna reserva solapada
     * con el período indicado. Se usa para resolver disponibilidad en lote y evitar
     * ejecutar una consulta de conteo por cada cancha (N+1).
     */
    @Query("SELECT DISTINCT r.cancha.id FROM Reserva r " +
           "WHERE r.cancha.id IN :canchaIds " +
           "AND r.estado != 'CANCELADA' " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Long> findCanchaIdsConSolapamiento(
            @Param("canchaIds") List<Long> canchaIds,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    /**
     * Trae la reserva junto con cancha -> establecimiento -> dueño y jugador en una sola
     * consulta, evitando 3 round-trips adicionales por lazy loading encadenado al
     * confirmar/cancelar (ver ReservaService). Se usa LEFT JOIN FETCH sobre el jugador
     * porque las reservas de mostrador (manuales) no tienen un jugador asociado.
     */
    @Query("SELECT r FROM Reserva r " +
           "LEFT JOIN FETCH r.jugador " +
           "JOIN FETCH r.cancha c " +
           "JOIN FETCH c.establecimiento e " +
           "JOIN FETCH e.dueno " +
           "WHERE r.id = :id")
    java.util.Optional<Reserva> findByIdConEstablecimientoYDueno(@Param("id") Long id);
 }