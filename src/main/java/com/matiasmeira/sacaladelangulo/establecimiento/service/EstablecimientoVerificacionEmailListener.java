package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía al dueño el email de aprobación o de rechazo de la verificación de su
 * establecimiento. AFTER_COMMIT + @Async por el mismo motivo que PruebaVencidaEmailListener:
 * el cambio de estadoVerificacion (y, si aplica, el arranque del trial) ya quedó persistido
 * antes de intentar el envío -- una falla del proveedor de email no revierte nada de eso -- y
 * no se retiene la conexión de base durante la latencia de la llamada de red externa.
 *
 * <p>Re-fetch del dueño por ID (no se recibe la entidad en el evento) porque @Async corre en
 * un hilo/persistence-context distinto al de la transacción original; se chequea deletedAt
 * explícitamente por si la cuenta se eliminó entre que se publicó el evento y que este
 * listener corre, mismo criterio que PruebaVencidaEmailListener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstablecimientoVerificacionEmailListener {

    private static final String ASUNTO_VERIFICADO = "¡Tu establecimiento fue verificado!";
    private static final String ASUNTO_RECHAZADO = "Tu solicitud de verificación fue rechazada";

    private final UsuarioRepository usuarioRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeVerificacion(EstablecimientoVerificadoEvent evento) {
        Usuario dueno = buscarDuenoActivo(evento.duenoId());
        if (dueno == null) {
            return;
        }

        String html = emailRenderer.render("establecimiento-verificado", Map.of(
                "nombre", dueno.getNombre(),
                "nombreEstablecimiento", evento.nombreEstablecimiento(),
                "ctaUrl", frontendUrl + "/panel"
        ));
        emailService.enviar(dueno.getEmail(), ASUNTO_VERIFICADO, html);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeRechazo(EstablecimientoRechazadoEvent evento) {
        Usuario dueno = buscarDuenoActivo(evento.duenoId());
        if (dueno == null) {
            return;
        }

        String html = emailRenderer.render("establecimiento-rechazado", Map.of(
                "nombre", dueno.getNombre(),
                "nombreEstablecimiento", evento.nombreEstablecimiento(),
                "motivo", evento.motivo(),
                "ctaUrl", frontendUrl + "/panel"
        ));
        emailService.enviar(dueno.getEmail(), ASUNTO_RECHAZADO, html);
    }

    private Usuario buscarDuenoActivo(Long duenoId) {
        Usuario dueno = usuarioRepository.findById(duenoId).orElse(null);
        if (dueno == null || dueno.getDeletedAt() != null) {
            log.warn("No se envía el email de verificación de establecimiento para el usuario {}: no existe o la cuenta fue eliminada",
                    duenoId);
            return null;
        }
        return dueno;
    }
}
