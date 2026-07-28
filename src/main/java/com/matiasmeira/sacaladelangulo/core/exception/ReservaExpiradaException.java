package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para una reserva cuya ventana de 10 minutos para
 * confirmar/pagar la seña ya venció. Se mapea a HTTP 410, mismo criterio que
 * TokenExpiradoException, para que el frontend distinga "se venció, hacé una
 * reserva nueva" de un error de validación genérico.
 */
public class ReservaExpiradaException extends RuntimeException {

    public ReservaExpiradaException(String message) {
        super(message);
    }
}
