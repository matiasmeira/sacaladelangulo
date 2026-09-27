package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcionLimites;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Degrada UN usuario de TRIAL a FREE, en su propia transacción. Bean aparte de
 * ExpiracionPruebaService (que orquesta el recorrido paginado) por el mismo motivo que
 * EmailPendienteRegistro está separado de EmailReintentoJob: Spring no aplica @Transactional
 * en self-invocation, así que llamar a este método como método privado del propio
 * orquestador silenciosamente no abriría ninguna transacción.
 *
 * <p>REQUIRES_NEW: cada usuario se procesa en su propia transacción corta, así que si uno
 * falla, el resto del lote sigue procesándose sin arrastrar el error — ExpiracionPruebaService
 * captura la excepción por usuario y continúa.
 *
 * <p>Además de bajar el plan, ajusta en la misma transacción los establecimientos/canchas
 * existentes del dueño para que cumplan la regla de seña de FREE (ver
 * PlanSuscripcionLimites, CanchaService.validarMontoSena / EstablecimientoService): a
 * diferencia del alta/edición (que sólo valida hacia adelante), un dueño que en TRIAL dejó la
 * seña en 0 no puede quedar en FREE violando esa misma regla. Si el ajuste falla, la
 * excepción revierte toda la transacción -- incluida la baja de plan -- y el usuario vuelve a
 * matchear el filtro TRIAL+vencido, así que ExpiracionPruebaService lo reintenta en la
 * próxima corrida del cron (ver su catch por usuario, que loguea el usuarioId).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradacionPlanService {

    private final UsuarioRepository usuarioRepository;
    private final AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final EstablecimientoRepository establecimientoRepository;
    private final CanchaRepository canchaRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void degradarPorVencimiento(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId).orElse(null);
        if (usuario == null) {
            log.warn("No se encontró el usuario {} al intentar degradar su plan vencido", usuarioId);
            return;
        }

        // Defensivo: si ya no está en TRIAL o la cuenta se eliminó entre que se armó el lote
        // y esta transacción, no hay nada que hacer (idempotente). Esto también hace
        // idempotente el ajuste de seña de abajo: una segunda corrida sobre el mismo usuario
        // ya degradado no vuelve a tocar sus establecimientos/canchas.
        if (usuario.getPlanSuscripcion() != PlanSuscripcion.TRIAL || usuario.getDeletedAt() != null) {
            return;
        }

        LocalDateTime fechaFinPrueba = usuario.getFechaFinPrueba();
        usuario.setPlanSuscripcion(PlanSuscripcion.FREE);
        usuarioRepository.save(usuario);

        AjusteSenaFree ajuste = ajustarSenaAMinimoFree(usuario);

        auditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan.builder()
                .usuario(usuario)
                .fechaHora(LocalDateTime.now())
                .detalle("Prueba vencida el " + fechaFinPrueba + ". Plan degradado de TRIAL a FREE. " + ajuste.resumen())
                .build());

        eventPublisher.publishEvent(new PruebaVencidaEvent(usuario.getId(), ajuste.nombresCanchasAjustadas()));
        log.info("Usuario {} degradado de TRIAL a FREE por vencimiento de prueba. {}", usuario.getId(), ajuste.resumen());
    }

    /**
     * Sube al mínimo de FREE los establecimientos/canchas ya existentes del dueño, para que
     * no queden violando la regla que el propio backend exige al crear/editar (ver
     * CanchaService.validarMontoSena, EstablecimientoService.esPlanLimitado). Sólo toca lo NO
     * eliminado: EstablecimientoRepository.findByDuenoIdAndDeletedAtIsNull y
     * CanchaRepository.findByEstablecimientoIdIn ya filtran deletedAt IS NULL cada uno, así
     * que un establecimiento o cancha eliminados no aparecen acá y no se tocan. Las canchas
     * desactivadas (isActive=false) SÍ se incluyen a propósito -- findByEstablecimientoIdIn no
     * filtra por isActive -- para que ya cumplan la regla si el dueño las reactiva más tarde.
     *
     * <p>El dueño tiene como máximo 3 establecimientos activos
     * (EstablecimientoService.LIMITE_ESTABLECIMIENTOS_ACTIVOS) y, en la práctica, un puñado de
     * canchas por cada uno: alcanza con cargar las entidades y guardarlas con
     * JpaRepository.saveAll, sin necesidad de una query de update masivo.
     */
    private AjusteSenaFree ajustarSenaAMinimoFree(Usuario usuario) {
        List<Establecimiento> establecimientos = establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(usuario.getId());

        int establecimientosAjustados = 0;
        for (Establecimiento establecimiento : establecimientos) {
            if (!Boolean.TRUE.equals(establecimiento.getRequiereSena())) {
                establecimiento.setRequiereSena(true);
                establecimientosAjustados++;
            }
        }
        if (!establecimientos.isEmpty()) {
            establecimientoRepository.saveAll(establecimientos);
        }

        List<Long> establecimientoIds = establecimientos.stream().map(Establecimiento::getId).toList();
        List<Cancha> canchas = establecimientoIds.isEmpty()
                ? List.of()
                : canchaRepository.findByEstablecimientoIdIn(establecimientoIds);

        List<Cancha> canchasAjustadas = new ArrayList<>();
        for (Cancha cancha : canchas) {
            if (cancha.getMontoSena() == null
                    || cancha.getMontoSena().compareTo(PlanSuscripcionLimites.SENA_MINIMA_PLAN_LIMITADO) < 0) {
                cancha.setMontoSena(PlanSuscripcionLimites.SENA_MINIMA_PLAN_LIMITADO);
                canchasAjustadas.add(cancha);
            }
        }
        if (!canchasAjustadas.isEmpty()) {
            canchaRepository.saveAll(canchasAjustadas);
        }

        List<String> nombresCanchasAjustadas = canchasAjustadas.stream().map(Cancha::getNombre).toList();
        return new AjusteSenaFree(establecimientosAjustados, nombresCanchasAjustadas);
    }

    private record AjusteSenaFree(int establecimientosAjustados, List<String> nombresCanchasAjustadas) {
        String resumen() {
            return "Ajuste de plan FREE: " + establecimientosAjustados + " establecimiento(s) con seña obligatoria, "
                    + nombresCanchasAjustadas.size() + " cancha(s) con seña ajustada a $"
                    + PlanSuscripcionLimites.SENA_MINIMA_PLAN_LIMITADO + ".";
        }
    }
}
