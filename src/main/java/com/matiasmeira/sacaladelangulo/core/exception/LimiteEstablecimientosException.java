package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para el tope de establecimientos: un OWNER (o el ADMIN
 * que crea en su nombre) no puede tener más de 3 establecimientos activos a
 * la vez. Se mapea a HTTP 400.
 */
public class LimiteEstablecimientosException extends RuntimeException {

    public LimiteEstablecimientosException(String message) {
        super(message);
    }
}
