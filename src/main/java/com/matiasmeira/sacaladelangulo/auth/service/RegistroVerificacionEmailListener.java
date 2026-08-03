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
 * Envía el email de verificación de registro fuera de la transacción que crea el token
 * (ver RegistroVerificacionService.iniciarRegistro): AFTER_COMMIT garantiza que el token ya
 * quedó persistido antes de intentar el envío (una falla del proveedor de email no hace
 * rollback del token), y @Async evita mantener la conexión de base de datos reservada
 * durante la latencia de una llamada de red externa.
 */
@Component
@RequiredArgsConstructor
public class RegistroVerificacionEmailListener {

    private static final String ASUNTO_VERIFICACION = "Verificá tu cuenta";
    private static final String ASUNTO_BIENVENIDA = "¡Bienvenido a Saque!";

    private final EmailService emailService;
    private final EmailRenderer emailRenderer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeVerificacion(VerificacionEmailSolicitadaEvent evento) {
        String html = emailRenderer.render("verificacion", Map.of(
                "linkVerificacion", evento.linkVerificacion(),
                "codigo", evento.codigo()
        ));
        emailService.enviar(evento.email(), ASUNTO_VERIFICACION, html);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeBienvenida(RegistroCompletadoEvent evento) {
        String html = emailRenderer.render("bienvenida", Map.of("nombre", evento.nombre()));
        emailService.enviar(evento.email(), ASUNTO_BIENVENIDA, html);
    }
}
