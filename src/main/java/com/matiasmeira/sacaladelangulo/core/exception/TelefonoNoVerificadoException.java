package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para un jugador que intenta reservar en un establecimiento que
 * exige teléfono verificado (Establecimiento.requiereTelefonoVerificado) sin tener
 * Usuario.telefonoVerificado en true. Se mapea a HTTP 403, mismo criterio que
 * JugadorBloqueadoException: es una restricción de autorización sobre una acción puntual,
 * modelada como excepción propia para que el frontend pueda distinguir este caso de un
 * bloqueo por parte del dueño.
 */
public class TelefonoNoVerificadoException extends RuntimeException {

    public TelefonoNoVerificadoException(String message) {
        super(message);
    }
}
