package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repositorio JPA para la entidad Establecimiento.
 */
@Repository
public interface EstablecimientoRepository extends JpaRepository<Establecimiento, Long> {

    List<Establecimiento> findByDuenoIdAndIsActiveTrue(Long duenoId);

    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true " +
           "AND (:deporte IS NULL OR c.deporte = :deporte) " +
           "AND (6371 * ACOS(COS(RADIANS(:latitud)) * COS(RADIANS(e.latitud)) * COS(RADIANS(e.longitud) - RADIANS(:longitud)) + SIN(RADIANS(:latitud)) * SIN(RADIANS(e.latitud)))) <= :distanciaKm")
    List<Establecimiento> findCercanosYPorDeporte(
            @Param("latitud") Double latitud,
            @Param("longitud") Double longitud,
            @Param("distanciaKm") Double distanciaKm,
            @Param("deporte") String deporte
    );
}
