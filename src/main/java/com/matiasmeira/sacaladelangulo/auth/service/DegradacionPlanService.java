package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradacionPlanService {

    private final UsuarioRepository usuarioRepository;
    private final AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void degradarPorVencimiento(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId).orElse(null);
        if (usuario == null) {
            log.warn("No se encontró el usuario {} al intentar degradar su plan vencido", usuarioId);
            return;
        }

        // Defensivo: si ya no está en TRIAL o la cuenta se eliminó entre que se armó el lote
        // y esta transacción, no hay nada que hacer (idempotente).
        if (usuario.getPlanSuscripcion() != PlanSuscripcion.TRIAL || usuario.getDeletedAt() != null) {
            return;
        }

        LocalDateTime fechaFinPrueba = usuario.getFechaFinPrueba();
        usuario.setPlanSuscripcion(PlanSuscripcion.FREE);
        usuarioRepository.save(usuario);

        auditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan.builder()
                .usuario(usuario)
                .fechaHora(LocalDateTime.now())
                .detalle("Prueba vencida el " + fechaFinPrueba + ". Plan degradado de TRIAL a FREE.")
                .build());

        eventPublisher.publishEvent(new PruebaVencidaEvent(usuario.getId()));
        log.info("Usuario {} degradado de TRIAL a FREE por vencimiento de prueba", usuario.getId());
    }
}
