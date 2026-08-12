package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    boolean existsBySlug(String slug);

    Optional<Establecimiento> findBySlugAndIsActiveTrue(String slug);

    /**
     * Variante de findCercanosYPorDeporte sin filtro geográfico: alimenta el listado
     * público cuando el visitante no compartió su ubicación (home sin filtros), donde
     * el orden relevante es el rating y no la distancia.
     */
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes)")
    List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte);

    /**
     * Trae, para el lote de ids indicado, las fotos (@ElementCollection ordenada) ya
     * inicializadas en la misma consulta: evita un SELECT de fotos por establecimiento al
     * armar la card pública (fotoPrincipal = primera foto). Las entidades que devuelve son,
     * dentro de la misma transacción, las mismas instancias gestionadas por la sesión que
     * ya trajo el listado principal — alcanza con llamar a este método por su efecto de
     * precarga; el caller sigue usando las entidades originales.
     */
    @EntityGraph(attributePaths = {"fotos"})
    @Query("SELECT e FROM Establecimiento e WHERE e.id IN :ids")
    List<Establecimiento> precargarFotos(@Param("ids") List<Long> ids);

    /**
     * Trae, para el lote de ids indicado, los horarios de atención ya inicializados en la
     * misma consulta: evita un SELECT de horarios por establecimiento al filtrar
     * candidatos por fecha/hora en la búsqueda pública (ver
     * ComplejoPublicoService.filtrarPorDisponibilidad). Mismo patrón de "precarga por
     * efecto" que precargarFotos: dentro de la misma transacción, las entidades que
     * devuelve son las mismas instancias que ya tiene el caller.
     */
    @EntityGraph(attributePaths = {"horariosAtencion"})
    @Query("SELECT e FROM Establecimiento e WHERE e.id IN :ids")
    List<Establecimiento> precargarHorarios(@Param("ids") List<Long> ids);
}
