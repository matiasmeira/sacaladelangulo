package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * Obtiene todas las reservas solapadas de un predio (establecimiento).
     * Excluye reservas canceladas (CANCELADA y CANCELADA_PRERESERVA) y trae las
     * canchas asociadas. Una PENDIENTE_SENA cuya ventana de 10 minutos ya venció
     * (expiraEn < ahora) tampoco cuenta como solapamiento: así el turno queda libre
     * apenas vence, sin esperar a que corra ReservaExpiracionService.
     *
     * @param estId ID del establecimiento
     * @param inicio Fecha y hora de inicio del período
     * @param fin Fecha y hora de fin del período
     * @param ahora Momento actual, usado para descartar PENDIENTE_SENA ya vencidas
     * @return Lista de reservas solapadas en el predio
     */
    @Query("SELECT r FROM Reserva r JOIN FETCH r.cancha c " +
           "WHERE c.establecimiento.id = :estId " +
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND (r.estado != 'PENDIENTE_SENA' OR r.expiraEn IS NULL OR r.expiraEn > :ahora) " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findSuperpuestas(
            @Param("estId") Long estId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("ahora") LocalDateTime ahora
    );

    @org.springframework.data.jpa.repository.Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId AND r.fechaHoraInicio < :finDia AND r.fechaHoraFin > :inicioDia AND r.estado NOT IN :estadosExcluidos")
    org.springframework.data.domain.Page<Reserva> findReservasEnRangoDiario(
            @org.springframework.data.repository.query.Param("canchaId") Long canchaId,
            @org.springframework.data.repository.query.Param("inicioDia") java.time.LocalDateTime inicioDia,
            @org.springframework.data.repository.query.Param("finDia") java.time.LocalDateTime finDia,
            @org.springframework.data.repository.query.Param("estadosExcluidos") List<EstadoReserva> estadosExcluidos,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Misma consulta que findReservasEnRangoDiario pero sin excluir ningún estado, para
     * cuando el dueño/admin pide explícitamente auditar cancelaciones históricas (ver B7
     * en la auditoría).
     */
    @org.springframework.data.jpa.repository.Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId AND r.fechaHoraInicio < :finDia AND r.fechaHoraFin > :inicioDia")
    org.springframework.data.domain.Page<Reserva> findReservasEnRangoDiarioIncluyendoCanceladas(
            @org.springframework.data.repository.query.Param("canchaId") Long canchaId,
            @org.springframework.data.repository.query.Param("inicioDia") java.time.LocalDateTime inicioDia,
            @org.springframework.data.repository.query.Param("finDia") java.time.LocalDateTime finDia,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT r FROM Reserva r WHERE r.cancha.id = :canchaId " +
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Reserva> findOverlappingByCanchaId(
            @Param("canchaId") Long canchaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    @Query("SELECT r FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.fechaHoraInicio BETWEEN :inicio AND :fin " +
           "AND r.estado NOT IN :estadosExcluidos")
    org.springframework.data.domain.Page<Reserva> findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(
            @Param("estId") Long estId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin,
            @Param("estadosExcluidos") List<EstadoReserva> estadosExcluidos,
            org.springframework.data.domain.Pageable pageable
    );

    /**
     * Misma consulta que findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn
     * pero sin excluir ningún estado (ver B7 en la auditoría).
     */
    org.springframework.data.domain.Page<Reserva> findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(
            Long estId,
            LocalDateTime inicio,
            LocalDateTime fin,
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
           "AND r.estado NOT IN ('CANCELADA', 'CANCELADA_PRERESERVA') " +
           "AND r.fechaHoraInicio < :fin AND r.fechaHoraFin > :inicio")
    List<Long> findCanchaIdsConSolapamiento(
            @Param("canchaIds") List<Long> canchaIds,
            @Param("inicio") LocalDateTime inicio,
            @Param("fin") LocalDateTime fin
    );

    /**
     * Libera (pasa a CANCELADA_PRERESERVA) todas las reservas PENDIENTE_SENA cuya
     * ventana de 10 minutos ya venció. Usado por ReservaExpiracionService; mismo
     * patrón de bulk-update que SolicitudIdempotenteRepository.borrarExpiradas.
     *
     * @param ahora Momento actual, contra el que se compara expiraEn
     * @return Cantidad de reservas liberadas
     */
    @Modifying
    @Query("UPDATE Reserva r SET r.estado = 'CANCELADA_PRERESERVA' " +
           "WHERE r.estado = 'PENDIENTE_SENA' AND r.expiraEn < :ahora")
    int liberarReservasVencidas(@Param("ahora") LocalDateTime ahora);

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