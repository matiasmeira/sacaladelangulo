package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía el mail de confirmación de baja de establecimiento fuera de la transacción que la
 * dispara (ver EstablecimientoEliminacionService), mismo motivo AFTER_COMMIT + @Async que
 * CuentaEliminadaEmailListener. Es una acción irreversible desde el lado del dueño (no hay
 * endpoint de restauración): si no fue él quien la pidió, tiene que enterarse cuanto antes.
 */
@Component
@RequiredArgsConstructor
public class EstablecimientoEliminadoEmailListener {

    private static final String ASUNTO = "Diste de baja tu establecimiento";

    private final EmailService emailService;
    private final EmailRenderer emailRenderer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeConfirmacion(EstablecimientoEliminadoEvent evento) {
        String html = emailRenderer.render("establecimiento-eliminado", Map.of(
                "nombreDueno", evento.nombreDueno(),
                "nombreEstablecimiento", evento.nombreEstablecimiento()));
        emailService.enviar(evento.emailDueno(), ASUNTO, html);
    }
}
