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
 * Envía el mail de confirmación de baja de cuenta fuera de la transacción que la dispara
 * (ver UsuarioEliminacionService), mismo motivo AFTER_COMMIT + @Async que
 * RecuperacionPasswordEmailListener. No necesita refetch de entidad: el evento ya lleva
 * el email y el nombre reales, capturados antes de anonimizar.
 */
@Component
@RequiredArgsConstructor
public class CuentaEliminadaEmailListener {

    private static final String ASUNTO = "Tu cuenta fue eliminada";

    private final EmailService emailService;
    private final EmailRenderer emailRenderer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeConfirmacion(CuentaEliminadaEvent evento) {
        String html = emailRenderer.render("cuenta-eliminada", Map.of("nombre", evento.nombre()));
        emailService.enviar(evento.email(), ASUNTO, html);
    }
}
