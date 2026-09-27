package com.matiasmeira.sacaladelangulo.core.email;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Acotada a los <a href="..."> que emiten nuestros propios templates (ver
    // src/main/resources/templates/email/): no parsea HTML de terceros ni de usuarios, así
    // que una regex alcanza sin sumar un parser HTML como dependencia.
    private static final Pattern HREF_PATTERN = Pattern.compile("href\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

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
        // El truncado de preview() deja afuera el link de verificación/recuperación (ver
        // layout.html: antepone cientos de caracteres de estilos antes del contenido real),
        // así que en dev/test se loguean también los links completos, aparte del preview.
        // En prod (sólo llega acá si alguien optó explícitamente por resend.enabled=false)
        // NO se extraen: son links con token y no pueden terminar en logs de producción.
        String links = environment.acceptsProfiles(Profiles.of("prod")) ? "" : extraerLinks(htmlBody);
        if (links.isEmpty()) {
            log.info("[EMAIL SIMULADO] Para: {} | Asunto: {} | Preview: {}", destinatario, asunto, preview(htmlBody));
        } else {
            log.info("[EMAIL SIMULADO] Para: {} | Asunto: {} | Preview: {} | Links: {}",
                    destinatario, asunto, preview(htmlBody), links);
        }
    }

    private String preview(String htmlBody) {
        if (htmlBody == null) {
            return "";
        }
        return htmlBody.length() > 120 ? htmlBody.substring(0, 120) + "..." : htmlBody;
    }

    private String extraerLinks(String htmlBody) {
        if (htmlBody == null) {
            return "";
        }
        // LinkedHashSet: deduplica preservando el orden de aparición (los templates repiten
        // el mismo link en el botón y en el texto de fallback).
        Set<String> links = new LinkedHashSet<>();
        Matcher matcher = HREF_PATTERN.matcher(htmlBody);
        while (matcher.find()) {
            links.add(HtmlUtils.htmlUnescape(matcher.group(1)));
        }
        return String.join(", ", links);
    }
}
