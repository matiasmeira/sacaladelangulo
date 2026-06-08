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

    List<Reserva> findByCanchaIdAndFechaHoraInicioBetweenAndEstadoNot(
            Long canchaId,
            LocalDateTime inicio,
            LocalDateTime fin,
            com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva estado
    );
}
