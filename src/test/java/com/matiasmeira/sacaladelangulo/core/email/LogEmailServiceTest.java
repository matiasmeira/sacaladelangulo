package com.matiasmeira.sacaladelangulo.core.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * El preview truncado a 120 caracteres deja afuera el link de verificación/recuperación
 * (ver layout.html: antepone cientos de caracteres de estilos antes del contenido real, ver
 * LogEmailService.preview()). Estos tests cubren el agregado de los links completos al log,
 * fuera del perfil prod, usando los templates REALES vía EmailRenderer.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("LogEmailService - links completos en el log de dev/test")
class LogEmailServiceTest {

    private final EmailRenderer emailRenderer = new EmailRenderer();

    @Test
    @DisplayName("enviar_SinPerfilProd_VerificacionReal_LoguearLinkCompletoUnaSolaVez")
    void enviar_SinPerfilProd_VerificacionReal_LoguearLinkCompletoUnaSolaVez(CapturedOutput output) {
        LogEmailService logEmailService = new LogEmailService(new MockEnvironment());
        String link = "http://localhost:3000/verificar?token=" + UUID.randomUUID();
        String html = emailRenderer.render("verificacion", Map.of("linkVerificacion", link, "codigo", "123456"));

        logEmailService.enviar("jugador@test.com", "Verificá tu cuenta", html);

        // El template repite el mismo link en el botón y en el texto de fallback: el log
        // tiene que deduplicarlo, no mostrarlo dos veces.
        assertThat(StringUtils.countOccurrencesOf(output.getOut(), link)).isEqualTo(1);
    }

    @Test
    @DisplayName("enviar_SinPerfilProd_RecuperarPasswordReal_LoguearLinkCompletoUnaSolaVez")
    void enviar_SinPerfilProd_RecuperarPasswordReal_LoguearLinkCompletoUnaSolaVez(CapturedOutput output) {
        LogEmailService logEmailService = new LogEmailService(new MockEnvironment());
        String link = "http://localhost:3000/restablecer?token=" + UUID.randomUUID();
        String html = emailRenderer.render("recuperar-password", Map.of("linkRecuperacion", link, "codigo", "654321"));

        logEmailService.enviar("jugador@test.com", "Recuperá tu contraseña", html);

        assertThat(StringUtils.countOccurrencesOf(output.getOut(), link)).isEqualTo(1);
    }

    @Test
    @DisplayName("enviar_PerfilProd_VerificacionReal_NoLogueaElToken")
    void enviar_PerfilProd_VerificacionReal_NoLogueaElToken(CapturedOutput output) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        LogEmailService logEmailService = new LogEmailService(environment);
        String token = UUID.randomUUID().toString();
        String html = emailRenderer.render("verificacion",
                Map.of("linkVerificacion", "http://localhost:3000/verificar?token=" + token, "codigo", "123456"));

        logEmailService.enviar("jugador@test.com", "Verificá tu cuenta", html);

        // En prod (sólo se llega acá con resend.enabled=false explícito) los links tienen
        // token y no pueden terminar en un log de producción.
        assertThat(output.getOut()).doesNotContain(token);
        assertThat(output.getOut()).doesNotContain("| Links:");
    }

    @Test
    @DisplayName("enviar_LinkSintenticoConDosParametros_DesescapaAmpersand")
    void enviar_LinkSintenticoConDosParametros_DesescapaAmpersand(CapturedOutput output) {
        LogEmailService logEmailService = new LogEmailService(new MockEnvironment());
        // Los links reales (token UUID, un solo query param) nunca generan "&amp;" -- este
        // es el único test que fuerza el caso, con un link sintético de dos parámetros.
        String html = emailRenderer.render("verificacion", Map.of(
                "linkVerificacion", "http://localhost:3000/verificar?token=abc&extra=1",
                "codigo", "123456"));

        // Precondición: si Thymeleaf dejara de escapar th:href (o cambiáramos el link de
        // ejemplo por uno sin "&"), este test dejaría de ejercitar el unescape sin avisar.
        assertThat(html).contains("&amp;");

        logEmailService.enviar("jugador@test.com", "Verificá tu cuenta", html);

        assertThat(output.getOut()).contains("token=abc&extra=1");
        assertThat(output.getOut()).doesNotContain("token=abc&amp;extra=1");
    }

    @Test
    @DisplayName("enviar_HtmlBodyNullOSinLinks_NoExplotaYLogueaComoSiempre")
    void enviar_HtmlBodyNullOSinLinks_NoExplotaYLogueaComoSiempre(CapturedOutput output) {
        LogEmailService logEmailService = new LogEmailService(new MockEnvironment());

        assertDoesNotThrow(() -> logEmailService.enviar("jugador@test.com", "Asunto", null));
        assertDoesNotThrow(() -> logEmailService.enviar("jugador@test.com", "Asunto", "<p>sin links</p>"));

        assertThat(output.getOut()).doesNotContain("| Links:");
    }
}
