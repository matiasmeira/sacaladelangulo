package com.matiasmeira.sacaladelangulo.core.email.reintento;

import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.core.email.EmailTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Única implementación de EmailService: intenta enviar y, si el proveedor falla, deja el
 * email encolado para que EmailReintentoJob lo reintente, en vez de perderlo.
 *
 * <p><b>Por qué no propaga la excepción.</b> Antes, un fallo de Resend subía hasta
 * AsyncConfig.getAsyncUncaughtExceptionHandler, se logueaba y ahí moría. Tragarla acá no
 * pierde información — la fila encolada ES el registro, y con más contexto que un log —
 * y además arregla un efecto colateral: OfertaMarketingBatchSender.enviarEnLotes no
 * captura por destinatario, así que una sola dirección que rebotaba abortaba el broadcast
 * entero para todos los que venían después.
 *
 * <p>Los 12 puntos de llamada del proyecto siguen usando EmailService sin cambios: la
 * política vive acá y no en cada listener.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailServiceConReintentos implements EmailService {

    private final EmailTransport emailTransport;
    private final EmailPendienteRegistro emailPendienteRegistro;

    @Override
    public void enviar(String destinatario, String asunto, String htmlBody) {
        try {
            // Fuera de transacción a propósito: es I/O de red (ver EmailPendienteRegistro).
            emailTransport.enviar(destinatario, asunto, htmlBody);
        } catch (RuntimeException ex) {
            log.warn("Falló el envío de email a {} (asunto: {}). Queda encolado para reintento.",
                    destinatario, asunto, ex);
            emailPendienteRegistro.encolar(destinatario, asunto, htmlBody, ex.getMessage());
            return;
        }

        // Recién con el envío confirmado se limpia lo que hubiera encolado: si se hiciera
        // antes y el envío fallara, se habría borrado un pendiente válido sin reemplazo.
        emailPendienteRegistro.resolver(destinatario, asunto);
    }
}
