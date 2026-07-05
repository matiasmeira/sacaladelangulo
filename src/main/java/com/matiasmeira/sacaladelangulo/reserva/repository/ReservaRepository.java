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
 }