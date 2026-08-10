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
     * Variante en lote para búsquedas que abarcan varios establecimientos a la vez (evita
     * hacer una consulta por establecimiento). @EntityGraph sobre "deportes": los callers
     * de este método filtran por c.getDeportes().contains(...) en memoria sobre el
     * resultado completo (hoy EstablecimientoService.buscarEstablecimientos; a partir de la
     * Tarea 7 de este plan, el filtro de disponibilidad por fecha/hora de
     * ComplejoPublicoService) — sin este fetch, ese filtro dispara un SELECT de deportes
     * por cada cancha (N+1).
     */
    @EntityGraph(attributePaths = {"deportes"})
    List<Cancha> findByEstablecimientoIdInAndIsActiveTrue(List<Long> establecimientoIds);

    /**
     * Trae, para el lote de establecimientos indicado, sus canchas activas con deportes y
     * tarifas ya inicializados en la misma consulta (@EntityGraph): alimenta las
     * derivaciones públicas (deportes/precioDesde/senaDesde por complejo, ver
     * ComplejoPublicoService) sin ejecutar una consulta de tarifas por cancha (N+1).
     * "tarifas" es la única colección tipo lista (bag) del grafo — "deportes" es un Set —
     * así que no cae en MultipleBagFetchException.
     */
    @EntityGraph(attributePaths = {"deportes", "tarifas"})
    @Query("SELECT c FROM Cancha c WHERE c.establecimiento.id IN :establecimientoIds AND c.isActive = true")
    List<Cancha> findActivasConDeportesYTarifasByEstablecimientoIdIn(@Param("establecimientoIds") List<Long> establecimientoIds);

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
