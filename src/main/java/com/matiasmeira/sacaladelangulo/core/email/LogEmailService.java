package com.matiasmeira.sacaladelangulo.core.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementación de EmailService que simula el envío logueando el mensaje por consola.
 * Activa por defecto (fallback de desarrollo): en cuanto se setea RESEND_ENABLED=true
 * (con RESEND_API_KEY configurada), ResendEmailService toma su lugar automáticamente sin
 * tocar perfiles ni código (ver ResendEmailService).
 *
 * Nota: la mutua exclusión se decide con un flag booleano propio (resend.enabled) y no
 * mirando directamente si resend.api-key está seteada, porque @ConditionalOnProperty sin
 * "havingValue" matchea cualquier valor que no sea el string "false" — con la condición
 * basada en la presencia de la api-key, en cuanto esa variable tenía un valor real
 * quedaban activos los dos EmailService a la vez (ambigüedad de bean al arrancar).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "resend", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LogEmailService implements EmailService {

    @Override
    public void enviarEmailVerificacion(String destinatario, String linkVerificacion) {
        log.info("[EMAIL SIMULADO] Para: {} | Verificá tu cuenta: {}", destinatario, linkVerificacion);
    }
}
