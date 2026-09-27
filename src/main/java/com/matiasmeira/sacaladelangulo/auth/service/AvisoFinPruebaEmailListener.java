package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcionLimites;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * Envía el email de aviso de fin de prueba gratuita (ver AvisoFinPruebaService).
 * AFTER_COMMIT + @Async por el mismo motivo que RegistroVerificacionEmailListener: el flag
 * de aviso enviado ya quedó persistido antes de intentar el envío, y no se retiene la
 * conexión de base de datos durante la latencia de una llamada de red externa. El evento
 * solo lleva el ID del usuario porque @Async corre en un hilo/persistence-context distinto
 * al de la transacción original, así que la entidad se vuelve a cargar acá.
 *
 * <p>A diferencia de PruebaVencidaEmailListener (que avisa DESPUÉS de que DegradacionPlanService
 * ya ajustó la seña), acá el usuario todavía está en TRIAL y nada se ajustó todavía: por eso
 * este listener sí puede recalcular en el momento qué canchas del dueño están hoy por debajo
 * del mínimo de FREE (ver PlanSuscripcionLimites) y avisarle qué va a pasar cuando venza la
 * prueba, en vez de recibir esa lista por evento.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvisoFinPruebaEmailListener {

    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final CanchaRepository canchaRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarAvisoFinPrueba(AvisoFinPruebaEvent evento) {
        Usuario usuario = usuarioRepository.findById(evento.usuarioId()).orElse(null);
        if (usuario == null) {
            log.warn("No se encontró el usuario {} al intentar enviar el aviso de fin de prueba", evento.usuarioId());
            return;
        }

        String html = emailRenderer.render("fin-prueba", Map.of(
                "nombre", usuario.getNombre(),
                "diasRestantes", evento.diasRestantes(),
                "canchasPorDebajoDelMinimo", canchasPorDebajoDelMinimo(usuario.getId())
        ));

        String dias = evento.diasRestantes() == 1 ? "día" : "días";
        String asunto = "Tu prueba gratuita termina en " + evento.diasRestantes() + " " + dias;
        emailService.enviar(usuario.getEmail(), asunto, html);
    }

    private List<String> canchasPorDebajoDelMinimo(Long duenoId) {
        List<Establecimiento> establecimientos = establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(duenoId);
        List<Long> establecimientoIds = establecimientos.stream().map(Establecimiento::getId).toList();
        if (establecimientoIds.isEmpty()) {
            return List.of();
        }
        return canchaRepository.findByEstablecimientoIdIn(establecimientoIds).stream()
                .filter(cancha -> cancha.getMontoSena() == null
                        || cancha.getMontoSena().compareTo(PlanSuscripcionLimites.SENA_MINIMA_PLAN_LIMITADO) < 0)
                .map(Cancha::getNombre)
                .toList();
    }
}
