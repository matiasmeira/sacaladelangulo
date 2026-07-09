package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad Cancha.
 */
@Repository
public interface CanchaRepository extends JpaRepository<Cancha, Long> {

    /**
     * Se trae "canchasFisicas" en la misma consulta (EntityGraph) para evitar N+1
     * al validar disponibilidad de pools de canchas (ver ReservaService.validarPoolCanchas).
     */
    @EntityGraph(attributePaths = "canchasFisicas")
    List<Cancha> findByEstablecimientoIdAndIsActiveTrue(Long establecimientoId);

    /**
     * Variante en lote para búsquedas que abarcan varios establecimientos a la vez
     * (evita hacer una consulta por establecimiento).
     */
    List<Cancha> findByEstablecimientoIdInAndIsActiveTrue(List<Long> establecimientoIds);
}
