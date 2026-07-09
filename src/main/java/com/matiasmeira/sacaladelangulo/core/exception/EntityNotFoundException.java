package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para recursos inexistentes. Se mapea a HTTP 404.
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }
}
