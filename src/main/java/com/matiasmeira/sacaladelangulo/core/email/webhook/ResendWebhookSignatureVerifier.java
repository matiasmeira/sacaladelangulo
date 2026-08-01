package com.matiasmeira.sacaladelangulo.core.email.webhook;

import com.matiasmeira.sacaladelangulo.core.exception.WebhookFirmaInvalidaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Verifica la firma Svix con la que Resend firma sus webhooks (headers svix-id,
 * svix-timestamp, svix-signature). Sin RESEND_WEBHOOK_SECRET configurada, el secreto
 * queda vacío y ninguna firma real puede validar contra él: el endpoint queda "cerrado"
 * por defecto en vez de romper el arranque de la app en entornos donde Resend todavía no
 * está configurado (ver ResendEmailService).
 */
@Component
public class ResendWebhookSignatureVerifier {

    private static final String ALGORITMO_HMAC = "HmacSHA256";
    private static final String PREFIJO_SECRETO = "whsec_";
    private static final Duration TOLERANCIA_TIMESTAMP = Duration.ofMinutes(5);

    private final String webhookSecret;

    public ResendWebhookSignatureVerifier(@Value("${resend.webhook-secret:}") String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    /**
     * @throws WebhookFirmaInvalidaException si el timestamp está fuera de tolerancia o
     *                                        ninguna firma del header coincide con la calculada
     */
    public void verificar(String svixId, String svixTimestamp, String svixSignature, String payload) {
        validarTimestamp(svixTimestamp);

        String firmaEsperada = calcularFirma(svixId, svixTimestamp, payload);
        boolean firmaValida = svixSignature != null && Arrays.stream(svixSignature.split(" "))
                .filter(valor -> valor.startsWith("v1,"))
                .map(valor -> valor.substring("v1,".length()))
                .anyMatch(valor -> constantTimeEquals(valor, firmaEsperada));

        if (!firmaValida) {
            throw new WebhookFirmaInvalidaException("Firma de webhook inválida");
        }
    }

    private void validarTimestamp(String svixTimestamp) {
        try {
            Instant timestamp = Instant.ofEpochSecond(Long.parseLong(svixTimestamp));
            if (Duration.between(timestamp, Instant.now()).abs().compareTo(TOLERANCIA_TIMESTAMP) > 0) {
                throw new WebhookFirmaInvalidaException("Timestamp de webhook fuera de tolerancia");
            }
        } catch (NumberFormatException e) {
            throw new WebhookFirmaInvalidaException("Timestamp de webhook inválido");
        }
    }

    private String calcularFirma(String svixId, String svixTimestamp, String payload) {
        try {
            String secretoSinPrefijo = webhookSecret.startsWith(PREFIJO_SECRETO)
                    ? webhookSecret.substring(PREFIJO_SECRETO.length())
                    : webhookSecret;
            byte[] claveDecodificada = Base64.getDecoder().decode(secretoSinPrefijo);

            Mac mac = Mac.getInstance(ALGORITMO_HMAC);
            mac.init(new SecretKeySpec(claveDecodificada, ALGORITMO_HMAC));

            String contenidoFirmado = svixId + "." + svixTimestamp + "." + payload;
            byte[] firma = mac.doFinal(contenidoFirmado.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(firma);
        } catch (Exception e) {
            throw new WebhookFirmaInvalidaException("No se pudo calcular la firma del webhook");
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
