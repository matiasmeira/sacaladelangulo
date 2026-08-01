package com.matiasmeira.sacaladelangulo.core.email.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint base para recibir webhooks de Resend. Por ahora solo verifica la firma y
 * loguea el evento recibido; el tracking real del ciclo de vida del email (bounces,
 * opens, etc.) queda para cuando exista un caso de uso concreto que lo necesite.
 * Público (ver SecurityConfig): la firma Svix es su único mecanismo de autenticación.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/resend")
@RequiredArgsConstructor
public class ResendWebhookController {

    private final ResendWebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Void> recibirWebhook(
            @RequestHeader("svix-id") String svixId,
            @RequestHeader("svix-timestamp") String svixTimestamp,
            @RequestHeader("svix-signature") String svixSignature,
            @RequestBody String payload) {
        signatureVerifier.verificar(svixId, svixTimestamp, svixSignature, payload);

        JsonNode evento = leerEvento(payload);
        log.info("Webhook de Resend recibido. Tipo: {}, Email ID: {}",
                evento.path("type").asText("desconocido"),
                evento.path("data").path("email_id").asText("desconocido"));

        return ResponseEntity.ok().build();
    }

    private JsonNode leerEvento(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("No se pudo parsear el payload del webhook de Resend como JSON", e);
            return objectMapper.getNodeFactory().objectNode();
        }
    }
}
