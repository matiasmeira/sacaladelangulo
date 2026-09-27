package com.matiasmeira.sacaladelangulo.core.email;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * Implementación de EmailService que simula el envío logueando el mensaje por consola.
 * Activa por defecto en dev/test (fallback de desarrollo): en cuanto se setea
 * RESEND_ENABLED=true (con RESEND_API_KEY configurada), ResendEmailService toma su lugar
 * automáticamente sin tocar perfiles ni código (ver ResendEmailService).
 *
 * <p>En el perfil "prod" el default de resend.enabled es "true" (ver
 * application-prod.properties), así que para que esta clase quede activa ahí alguien tuvo
 * que optar explícitamente por RESEND_ENABLED=false — no es un olvido silencioso. Por eso
 * se logueamos distinto según el perfil: WARN en prod (algo se desactivó a propósito, hay
 * que verlo), INFO en cualquier otro perfil (es el comportamiento esperado de fábrica).
 *
 * <p>Nota: la mutua exclusión se decide con un flag booleano propio (resend.enabled) y no
 * mirando directamente si resend.api-key está seteada, porque @ConditionalOnProperty sin
 * "havingValue" matchea cualquier valor que no sea el string "false" — con la condición
 * basada en la presencia de la api-key, en cuanto esa variable tenía un valor real
 * quedaban activos los dos EmailService a la vez (ambigüedad de bean al arrancar).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "resend", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LogEmailService implements EmailTransport {

    private final Environment environment;

    @PostConstruct
    void avisarQueLosMailsSeSimulan() {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            log.warn("resend.enabled=false con perfil prod: los mails se simulan por log y no se envían.");
        } else {
            log.info("LogEmailService activo: los mails se simulan por log y no se envían.");
        }
    }

    @Override
    public void enviar(String destinatario, String asunto, String htmlBody) {
        log.info("[EMAIL SIMULADO] Para: {} | Asunto: {} | Preview: {}", destinatario, asunto, preview(htmlBody));
    }

    private String preview(String htmlBody) {
        if (htmlBody == null) {
            return "";
        }
        return htmlBody.length() > 120 ? htmlBody.substring(0, 120) + "..." : htmlBody;
    }
}
