package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    private final EmailService emailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeVerificacion(VerificacionEmailSolicitadaEvent evento) {
        emailService.enviarEmailVerificacion(evento.email(), evento.linkVerificacion());
    }
}
