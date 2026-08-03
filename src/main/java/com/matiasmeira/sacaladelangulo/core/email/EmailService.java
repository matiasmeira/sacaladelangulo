package com.matiasmeira.sacaladelangulo.core.email;

/**
 * Abstracción genérica para el envío de emails transaccionales. La única implementación
 * activa por defecto (LogEmailService) simula el envío por log; al integrar un proveedor
 * real (ej. Resend) se agrega una nueva implementación sin tocar a los servicios que
 * dependen de esta interfaz. El asunto y el cuerpo (HTML) ya vienen armados por el
 * llamador (ver EmailRenderer) para que esta interfaz no conozca ninguna plantilla
 * concreta y sirva por igual para verificación, recuperación de cuenta, notificaciones
 * de reserva, etc. (Fases 1-6).
 */
public interface EmailService {

    void enviar(String destinatario, String asunto, String htmlBody);
}
