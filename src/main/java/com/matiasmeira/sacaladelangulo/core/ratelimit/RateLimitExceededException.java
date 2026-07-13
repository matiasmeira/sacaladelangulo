package com.matiasmeira.sacaladelangulo.core.ratelimit;

/**
 * Se lanza cuando una clave (IP, usuario, empleado, etc.) agotó sus intentos
 * disponibles dentro de la ventana de tiempo configurada.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
