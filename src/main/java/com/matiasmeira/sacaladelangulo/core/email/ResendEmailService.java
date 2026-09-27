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
 *
 * <p>En prod, resend.enabled es true por default (ver application-prod.properties) y
 * resend.api-key no tiene default, así que la sola ausencia de RESEND_API_KEY ya tumba el
 * arranque por placeholder sin resolver. Pero el SDK de Resend (Resend/Emails) no valida la
 * key al construirse — con RESEND_API_KEY="" el cliente se construiría igual y el fallo
 * recién aparecería en el primer envío real, encolado silenciosamente para reintento. El
 * guard de este constructor cierra ese hueco fallando también al arrancar.
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
        this(new Resend(validarApiKey(apiKey)), remitente);
    }

    ResendEmailService(Resend resendClient, String remitente) {
        this.resendClient = resendClient;
        this.remitente = remitente;
    }

    private static String validarApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "resend.enabled=true pero resend.api-key está vacío: seteá RESEND_API_KEY con una key "
                            + "válida, o RESEND_ENABLED=false si querés volver a mails simulados.");
        }
        return apiKey;
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
