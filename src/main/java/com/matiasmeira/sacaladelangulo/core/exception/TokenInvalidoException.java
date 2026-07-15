package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para un token de verificación que no existe. Se mapea a HTTP 400.
 */
public class TokenInvalidoException extends RuntimeException {

    public TokenInvalidoException(String message) {
        super(message);
    }
}
