package com.matiasmeira.sacaladelangulo.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Habilita @Async, usado hoy por RegistroVerificacionEmailListener para desacoplar el
 * envío del email de verificación de la transacción que crea el token (ver A12 en la
 * auditoría): sin esto, el envío se ejecutaría igual pero de forma sincrónica.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
