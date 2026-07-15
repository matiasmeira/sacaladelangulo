package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para un token de verificación que existió pero venció. Se mapea a
 * HTTP 410, para que el frontend pueda distinguir "pedí un link nuevo" de un token inválido.
 */
public class TokenExpiradoException extends RuntimeException {

    public TokenExpiradoException(String message) {
        super(message);
    }
}
