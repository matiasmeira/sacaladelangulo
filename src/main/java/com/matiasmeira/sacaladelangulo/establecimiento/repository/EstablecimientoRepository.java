package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * "Mis establecimientos" del panel del dueño. Sigue llamándose sin "AndDeletedAtIsNull"
     * en el nombre a propósito: bajo el invariante que mantiene EstablecimientoEliminacionService
     * (sólo se puede eliminar un establecimiento ya deshabilitado), deletedAt != null implica
     * isActive = false, así que el filtro de acá es defensivo -- no cambia el resultado para
     * ningún caller existente, sólo evita depender para siempre de ese invariante. Se optó por
     * @Query en vez de renombrar el derived query para no forzar un cambio de firma en callers
     * cuyo comportamiento no cambia.
     */
    @Query("SELECT e FROM Establecimiento e WHERE e.dueno.id = :duenoId AND e.isActive = true AND e.deletedAt IS NULL")
    List<Establecimiento> findByDuenoIdAndIsActiveTrue(@Param("duenoId") Long duenoId);

    long countByDuenoIdAndIsActiveTrue(Long duenoId);

    /**
     * Cuenta los establecimientos del dueño que NO están eliminados, estén habilitados o no:
     * usado por el límite de 3 establecimientos por dueño (ver
     * EstablecimientoService#crearEstablecimiento). A propósito no filtra por isActive --
     * un establecimiento deshabilitado sigue ocupando un lugar (sigue teniendo canchas,
     * reservas, turnos fijos y slug reservado), así que deshabilitarlo no libera cupo. Si el
     * límite mirara solo los activos, deshabilitar y rehabilitar establecimientos dejaría el
     * tope de 3 en la práctica sin efecto. Eliminar SÍ libera cupo -- por eso, a diferencia de
     * isActive, deletedAt sí forma parte del criterio acá (ver javadoc de
     * EstablecimientoService#crearEstablecimiento).
     */
    long countByDuenoIdAndDeletedAtIsNull(Long duenoId);

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
           "WHERE e.isActive = true AND e.estadoVerificacion = 'VERIFICADO' AND e.deletedAt IS NULL " +
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

    /**
     * Establecimiento operativo de cara al jugador por slug: isActive Y estadoVerificacion =
     * VERIFICADO, mismo criterio (y mismo nombre de concepto, "operativo") que
     * EstablecimientoOperativoGuard.validarEstablecimientoOperativoParaJugador. No es un
     * derived query name porque "AndEstadoVerificacion" además necesitaría el valor VERIFICADO
     * como parámetro en cada call site -- eso mueve el criterio al caller y es exactamente el
     * tipo de duplicación que se quiere evitar (ver EstablecimientoOperativoCoherenciaTest,
     * que ata este método al guard). El criterio ahora también incluye deletedAt IS NULL,
     * por el mismo motivo.
     */
    @Query("SELECT e FROM Establecimiento e WHERE e.slug = :slug AND e.isActive = true AND e.estadoVerificacion = 'VERIFICADO' AND e.deletedAt IS NULL")
    Optional<Establecimiento> findBySlugOperativo(@Param("slug") String slug);

    /**
     * Variante de findCercanosYPorDeporte sin filtro geográfico: alimenta el listado
     * público cuando el visitante no compartió su ubicación (home sin filtros), donde
     * el orden relevante es el rating y no la distancia. Acotada por el Pageable que le
     * pase el caller: sin ubicación no hay límite geográfico natural, así que
     * ComplejoPublicoService le pasa un tope fijo de filas (ver M-final-2 / Goal 3 del
     * follow-up de zona pública).
     */
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true AND e.estadoVerificacion = 'VERIFICADO' AND e.deletedAt IS NULL " +
           "AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes)")
    List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte, Pageable pageable);

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

    /**
     * Listado paginado para el panel de admin de verificación manual
     * (AdminEstablecimientoController), acotado a un estado puntual (ej. EN_REVISION).
     * EntityGraph sobre "dueno" para no pagar un SELECT extra por fila al armar
     * AdminEstablecimientoResponse (nombre + email del dueño), mismo criterio que
     * precargarFotos/precargarHorarios.
     *
     * <p>A diferencia de findByDuenoIdAndIsActiveTrue, acá el nombre SÍ incluye
     * "AndDeletedAtIsNull": este listado, a propósito, no filtra por isActive (el admin
     * necesita ver también los deshabilitados), así que el invariante "eliminado implica
     * inactivo" no alcanza para excluirlos -- sin esta condición explícita, un
     * establecimiento eliminado seguiría apareciendo en la cola de verificación.
     */
    @EntityGraph(attributePaths = {"dueno"})
    Page<Establecimiento> findByEstadoVerificacionAndDeletedAtIsNull(EstadoVerificacion estadoVerificacion, Pageable pageable);

    /**
     * Variante de findByEstadoVerificacionAndDeletedAtIsNull sin filtro, para cuando el admin
     * no elige un estado puntual (ver AdminEstablecimientoController). "findAllBy" es el
     * nombre derivado que Spring Data reconoce para "todos", necesario para poder seguir
     * sumándole el EntityGraph (JpaRepository.findAll(Pageable) no admite anotarse).
     */
    @EntityGraph(attributePaths = {"dueno"})
    Page<Establecimiento> findAllByDeletedAtIsNull(Pageable pageable);
}
