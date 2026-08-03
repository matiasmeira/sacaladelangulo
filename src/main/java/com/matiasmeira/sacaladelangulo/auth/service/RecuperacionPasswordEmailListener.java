package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía los emails del flujo de recuperación de contraseña fuera de la transacción que los
 * dispara (ver RecuperacionPasswordService), mismo motivo que RegistroVerificacionEmailListener:
 * AFTER_COMMIT garantiza que el cambio ya quedó persistido antes de intentar el envío, y
 * @Async evita mantener la conexión de base de datos reservada durante la latencia de una
 * llamada de red externa. Ambos listeners viven en la misma clase porque los dos eventos
 * pertenecen al mismo dominio (recuperación de contraseña).
 */
@Component
@RequiredArgsConstructor
public class RecuperacionPasswordEmailListener {

    private static final String ASUNTO_RECUPERACION = "Recuperá tu contraseña";
    private static final String ASUNTO_PASSWORD_CAMBIADA = "Tu contraseña fue cambiada";

    private final EmailService emailService;
    private final EmailRenderer emailRenderer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeRecuperacion(RecuperacionPasswordSolicitadaEvent evento) {
        String html = emailRenderer.render("recuperar-password", Map.of(
                "linkRecuperacion", evento.linkRecuperacion(),
                "codigo", evento.codigo()
        ));
        emailService.enviar(evento.email(), ASUNTO_RECUPERACION, html);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDePasswordCambiada(PasswordCambiadaEvent evento) {
        String html = emailRenderer.render("password-cambiada", Map.of());
        emailService.enviar(evento.email(), ASUNTO_PASSWORD_CAMBIADA, html);
    }
}
