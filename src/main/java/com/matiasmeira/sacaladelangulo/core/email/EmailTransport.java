package com.matiasmeira.sacaladelangulo.core.email;

/**
 * El envío crudo contra el proveedor, sin ninguna política alrededor. Existe para separar
 * DOS responsabilidades que antes vivían juntas en EmailService: "hablar con el
 * proveedor" (esto) y "qué hacer cuando el proveedor falla" (EmailServiceConReintentos,
 * la única implementación de EmailService).
 *
 * <p>Los llamadores de negocio NO deben depender de esta interfaz: siguen usando
 * EmailService, que es el que garantiza que un fallo no se pierda. Se inyecta acá sólo
 * desde el decorador y desde el job de reintento — este último a propósito, porque pasar
 * por el decorador volvería a encolar lo que ya está encolado.
 *
 * <p>Implementaciones mutuamente excluyentes por {@code resend.enabled}: LogEmailService
 * (simula) y ResendEmailService (envía de verdad).
 */
public interface EmailTransport {

    void enviar(String destinatario, String asunto, String htmlBody);
}
