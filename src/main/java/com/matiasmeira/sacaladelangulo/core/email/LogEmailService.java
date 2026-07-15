package com.matiasmeira.sacaladelangulo.core.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Implementación de EmailService que simula el envío logueando el mensaje por consola.
 * Punto de reemplazo directo para integrar un proveedor real (ej. Resend) más adelante.
 */
@Slf4j
@Service
public class LogEmailService implements EmailService {

    @Override
    public void enviarEmailVerificacion(String destinatario, String linkVerificacion) {
        log.info("[EMAIL SIMULADO] Para: {} | Verificá tu cuenta: {}", destinatario, linkVerificacion);
    }
}
