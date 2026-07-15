package com.matiasmeira.sacaladelangulo.core.email;

/**
 * Abstracción para el envío de emails transaccionales. La única implementación hoy
 * (LogEmailService) simula el envío por log; al integrar un proveedor real (ej. Resend)
 * se agrega una nueva implementación sin tocar a los servicios que dependen de esta interfaz.
 */
public interface EmailService {

    void enviarEmailVerificacion(String destinatario, String linkVerificacion);
}
