package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción para un webhook entrante cuya firma no se pudo validar (ver
 * ResendWebhookSignatureVerifier). Se mapea a HTTP 401: el endpoint es público
 * (no requiere JWT), la firma es el único mecanismo de autenticación que tiene.
 */
public class WebhookFirmaInvalidaException extends RuntimeException {

    public WebhookFirmaInvalidaException(String message) {
        super(message);
    }
}
