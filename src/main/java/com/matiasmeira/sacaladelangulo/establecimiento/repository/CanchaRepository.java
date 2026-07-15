package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Repositorio JPA para la entidad Cancha.
 */
@Repository
public interface CanchaRepository extends JpaRepository<Cancha, Long> {

    /**
     * Se traen "canchasFisicas" y "deportes" en la misma consulta (EntityGraph) para
     * evitar N+1: canchasFisicas al validar disponibilidad de pools de canchas (ver
     * ReservaService.validarPoolCanchas / PoolCanchaCalculator), deportes al armar la
     * grilla de disponibilidad para varias canchas a la vez (ver DisponibilidadService).
     */
    @EntityGraph(attributePaths = {"canchasFisicas", "deportes"})
    List<Cancha> findByEstablecimientoIdAndIsActiveTrue(Long establecimientoId);

    /**
     * Variante en lote para búsquedas que abarcan varios establecimientos a la vez
     * (evita hacer una consulta por establecimiento).
     */
    List<Cancha> findByEstablecimientoIdInAndIsActiveTrue(List<Long> establecimientoIds);

    /**
     * Adquiere un lock pesimista (SELECT ... FOR UPDATE) sobre las canchas indicadas, en
     * el orden ascendente de ID de la propia query. Se usa como mutex antes de validar y
     * crear/mover una reserva, para serializar entre sí a las transacciones concurrentes
     * que compiten por la misma cancha o por canchas que comparten un pool con ella (ver
     * PoolCanchaCalculator.canchasRelacionadas), evitando el doble-booking por condición
     * de carrera. Todos los llamadores deben pasar los IDs ya ordenados ascendentemente
     * para que el orden de adquisición de locks sea siempre el mismo y no se produzcan
     * deadlocks entre transacciones que bloquean el mismo conjunto de canchas.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Cancha c WHERE c.id IN :ids ORDER BY c.id ASC")
    List<Cancha> lockPorIds(@Param("ids") Collection<Long> ids);
}
