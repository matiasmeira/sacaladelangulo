package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repositorio JPA para la entidad Usuario.
 */
@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * IgnoreCase para que "Juan" y "juan" (o con espacios extra, ya trimeados por el
     * llamador) se traten como el mismo empleado tanto al loguear como al validar
     * unicidad de nombre (ver B4 en la auditoría).
     */
    Optional<Usuario> findByEstablecimientoIdAndNombreIgnoreCaseAndRol(Long establecimientoId, String nombre, Role rol);

    /**
     * AndIsActiveTrue para que el nombre de un empleado desactivado quede libre y se
     * pueda reutilizar al dar de alta a otro empleado (ver B18 en la auditoría): sin
     * este filtro, un nombre queda bloqueado para siempre apenas se desactiva a quien
     * lo tenía.
     */
    boolean existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(Long establecimientoId, String nombre, Role rol);

    List<Usuario> findByEstablecimientoIdAndRol(Long establecimientoId, Role rol);

    List<Usuario> findByEstablecimientoIdAndRolAndIsActiveTrue(Long establecimientoId, Role rol);

    /**
     * Usuarios cuya prueba gratuita vence dentro de la ventana [desde, hasta) y que todavía
     * no recibieron el aviso de ese umbral (ver AvisoFinPruebaService, Fase 5): el llamador
     * pasa el rango correspondiente al día calendario que cae exactamente N días desde hoy.
     * AndDeletedAtIsNull para no mandarle el aviso a una cuenta ya eliminada: el email ya es
     * el placeholder @saque.deleted (ver UsuarioEliminacionService) y un envío ahí genera un
     * bounce contra la reputación del dominio real en Resend (mismo criterio que
     * ReservaNotificacionListener.puedeNotificar).
     */
    List<Usuario> findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(LocalDateTime desde, LocalDateTime hasta);

    List<Usuario> findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(LocalDateTime desde, LocalDateTime hasta);

    List<Usuario> findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(LocalDateTime desde, LocalDateTime hasta);

    /**
     * Usuarios OWNER en TRIAL cuya prueba gratuita ya venció (ver ExpiracionPruebaService,
     * degradación automática a FREE). AndDeletedAtIsNull por el mismo motivo que los finders
     * de aviso de arriba: no tocar una cuenta ya eliminada. Paginado porque, a diferencia del
     * rango acotado a un día calendario de los finders de aviso, acá no hay tope natural al
     * volumen (todo TRIAL vencido hasta la fecha, sin importar desde cuándo).
     */
    Page<Usuario> findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
            PlanSuscripcion planSuscripcion, LocalDateTime ahora, Pageable pageable);

    /**
     * Usado para resolver el token del link de "darme de baja" de un email de marketing
     * (ver Fase 6): no requiere autenticación, así que el token opaco es la única forma
     * de identificar al usuario.
     */
    Optional<Usuario> findByUnsubscribeToken(String token);

    /**
     * Paginado porque el broadcast de ofertas (ver OfertaMarketingBatchSender) puede
     * recorrer potencialmente todos los usuarios con opt-in: cargarlos todos de una vez
     * en memoria no escala.
     */
    Page<Usuario> findByAceptaMarketingTrue(Pageable pageable);
}
