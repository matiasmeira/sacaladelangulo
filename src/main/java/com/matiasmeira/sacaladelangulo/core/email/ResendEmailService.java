package com.matiasmeira.sacaladelangulo.core.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementación de EmailService que envía de verdad a través de la API de Resend. Se
 * activa seteando RESEND_ENABLED=true (además de RESEND_API_KEY, requerida para
 * construir el cliente; ver LogEmailService para el fallback de desarrollo y por qué la
 * activación se decide con un flag booleano propio en vez de mirar la api-key directamente).
 * Se invoca siempre de forma asíncrona y después del commit de la transacción que la
 * origina (ver RegistroVerificacionEmailListener y AsyncConfig, A12 en la auditoría), así
 * que una excepción acá nunca hace rollback de nada: la captura AsyncConfig.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "resend", name = "enabled", havingValue = "true")
public class ResendEmailService implements EmailTransport {

    private final Resend resendClient;
    private final String remitente;

    @Autowired
    public ResendEmailService(@Value("${resend.api-key}") String apiKey,
                               @Value("${app.mail.from}") String remitente) {
        this(new Resend(apiKey), remitente);
    }

    ResendEmailService(Resend resendClient, String remitente) {
        this.resendClient = resendClient;
        this.remitente = remitente;
    }

    @Override
    public void enviar(String destinatario, String asunto, String htmlBody) {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(remitente)
                .to(destinatario)
                .subject(asunto)
                .html(htmlBody)
                .build();
        try {
            resendClient.emails().send(params);
        } catch (ResendException e) {
            log.error("Fallo al enviar email a {} vía Resend", destinatario, e);
            throw new RuntimeException("No se pudo enviar el email", e);
        }
    }
}
