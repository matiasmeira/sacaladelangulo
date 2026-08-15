package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para el guardrail de baja de cuenta: un OWNER (o el ADMIN que
 * intenta eliminarlo sin forzar) no puede eliminar la cuenta mientras tenga
 * establecimientos activos. Se mapea a HTTP 400.
 */
public class EstablecimientosActivosException extends RuntimeException {

    public EstablecimientosActivosException(String message) {
        super(message);
    }
}
