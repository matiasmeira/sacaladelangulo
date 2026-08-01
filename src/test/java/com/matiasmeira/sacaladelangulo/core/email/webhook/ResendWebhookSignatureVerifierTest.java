package com.matiasmeira.sacaladelangulo.core.email.webhook;

import com.matiasmeira.sacaladelangulo.core.exception.WebhookFirmaInvalidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ResendWebhookSignatureVerifier - Tests de verificación de firma Svix")
class ResendWebhookSignatureVerifierTest {

    private static final String CLAVE_BASE64 = Base64.getEncoder()
            .encodeToString("clave-de-prueba-1234567890123456".getBytes(StandardCharsets.UTF_8));
    private static final String WEBHOOK_SECRET = "whsec_" + CLAVE_BASE64;

    private ResendWebhookSignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new ResendWebhookSignatureVerifier(WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("verificar_Exito_FirmaValida")
    void verificar_Exito_FirmaValida() {
        String svixId = "msg_123";
        String svixTimestamp = String.valueOf(Instant.now().getEpochSecond());
        String payload = "{\"type\":\"email.sent\"}";
        String firma = firmar(svixId, svixTimestamp, payload);

        assertDoesNotThrow(() -> verifier.verificar(svixId, svixTimestamp, "v1," + firma, payload));
    }

    @Test
    @DisplayName("verificar_Fallo_FirmaInvalida")
    void verificar_Fallo_FirmaInvalida() {
        String svixId = "msg_123";
        String svixTimestamp = String.valueOf(Instant.now().getEpochSecond());
        String payload = "{\"type\":\"email.sent\"}";

        assertThrows(WebhookFirmaInvalidaException.class,
                () -> verifier.verificar(svixId, svixTimestamp, "v1,firmaFalsa", payload));
    }

    @Test
    @DisplayName("verificar_Fallo_PayloadAlterado")
    void verificar_Fallo_PayloadAlterado() {
        String svixId = "msg_123";
        String svixTimestamp = String.valueOf(Instant.now().getEpochSecond());
        String firma = firmar(svixId, svixTimestamp, "{\"type\":\"email.sent\"}");

        assertThrows(WebhookFirmaInvalidaException.class,
                () -> verifier.verificar(svixId, svixTimestamp, "v1," + firma, "{\"type\":\"email.bounced\"}"));
    }

    @Test
    @DisplayName("verificar_Fallo_TimestampVencido")
    void verificar_Fallo_TimestampVencido() {
        String svixId = "msg_123";
        String svixTimestamp = String.valueOf(Instant.now().minus(Duration.ofMinutes(10)).getEpochSecond());
        String payload = "{\"type\":\"email.sent\"}";
        String firma = firmar(svixId, svixTimestamp, payload);

        assertThrows(WebhookFirmaInvalidaException.class,
                () -> verifier.verificar(svixId, svixTimestamp, "v1," + firma, payload));
    }

    @Test
    @DisplayName("verificar_Fallo_SecretoNoConfigurado")
    void verificar_Fallo_SecretoNoConfigurado() {
        ResendWebhookSignatureVerifier verifierSinSecreto = new ResendWebhookSignatureVerifier("");
        String svixId = "msg_123";
        String svixTimestamp = String.valueOf(Instant.now().getEpochSecond());

        assertThrows(WebhookFirmaInvalidaException.class,
                () -> verifierSinSecreto.verificar(svixId, svixTimestamp, "v1,cualquiercosa", "{}"));
    }

    private String firmar(String svixId, String svixTimestamp, String payload) {
        try {
            byte[] clave = Base64.getDecoder().decode(CLAVE_BASE64);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clave, "HmacSHA256"));
            byte[] firma = mac.doFinal((svixId + "." + svixTimestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(firma);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
