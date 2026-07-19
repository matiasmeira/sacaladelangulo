package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
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

    /**
     * Pre-filtro por bounding box de latitud/longitud (comparación numérica simple, sí
     * indexable) antes de calcular Haversine exacto: sin esto, la fórmula trigonométrica
     * corre contra todas las filas activas de la tabla en cada búsqueda (endpoint público,
     * sin autenticación), incluso las que están a miles de km de distancia. El box se
     * calcula generosamente ancho (111.045 km/grado real, se usa 110 para asegurar que
     * nunca excluya un punto válido por redondeo) — el Haversine exacto de abajo sigue
     * siendo el filtro final real (ver M29 en la auditoría).
     */
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true " +
           "AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes) " +
           "AND e.latitud BETWEEN (:latitud - (:distanciaKm / 110.0)) AND (:latitud + (:distanciaKm / 110.0)) " +
           "AND e.longitud BETWEEN (:longitud - (:distanciaKm / (110.0 * COS(RADIANS(:latitud))))) AND (:longitud + (:distanciaKm / (110.0 * COS(RADIANS(:latitud))))) " +
           "AND (6371 * ACOS(COS(RADIANS(:latitud)) * COS(RADIANS(e.latitud)) * COS(RADIANS(e.longitud) - RADIANS(:longitud)) + SIN(RADIANS(:latitud)) * SIN(RADIANS(e.latitud)))) <= :distanciaKm")
    List<Establecimiento> findCercanosYPorDeporte(
            @Param("latitud") Double latitud,
            @Param("longitud") Double longitud,
            @Param("distanciaKm") Double distanciaKm,
            @Param("deporte") Deporte deporte
    );
}
