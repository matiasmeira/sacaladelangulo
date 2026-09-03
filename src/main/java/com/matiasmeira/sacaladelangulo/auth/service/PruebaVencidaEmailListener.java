package com.matiasmeira.sacaladelangulo.auth.service;

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
 * Envía el email de aviso de degradación de plan (TRIAL vencido -> FREE). AFTER_COMMIT +
 * @Async por el mismo motivo que AvisoFinPruebaEmailListener: el cambio de planSuscripcion ya
 * quedó persistido antes de intentar el envío, y no se retiene la conexión de base durante la
 * latencia de una llamada de red externa. Re-fetch de la entidad porque @Async corre en un
 * hilo/persistence-context distinto al de la transacción original.
 *
 * <p>Chequea deletedAt explícitamente (no solo que el usuario exista): entre que se publica
 * el evento y que este listener corre, la cuenta pudo haberse eliminado/anonimizado, y un
 * envío a esa altura pegaría contra el placeholder @saque.deleted (mismo criterio que
 * UsuarioRepository.findByFechaFinPruebaBetween...AndDeletedAtIsNull).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PruebaVencidaEmailListener {

    private static final String ASUNTO = "Tu prueba gratuita terminó";

    private final UsuarioRepository usuarioRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarAvisoDeDegradacion(PruebaVencidaEvent evento) {
        Usuario usuario = usuarioRepository.findById(evento.usuarioId()).orElse(null);
        if (usuario == null || usuario.getDeletedAt() != null) {
            log.warn("No se envía el aviso de degradación de plan para el usuario {}: no existe o la cuenta fue eliminada",
                    evento.usuarioId());
            return;
        }

        String html = emailRenderer.render("prueba-vencida", Map.of(
                "nombre", usuario.getNombre(),
                "ctaUrl", frontendUrl + "/panel/configuracion"
        ));

        emailService.enviar(usuario.getEmail(), ASUNTO, html);
    }
}
