package com.matiasmeira.sacaladelangulo.core.imagekit;

/**
 * Falla al hablar con ImageKit (red, credenciales, respuesta incompleta). Se mapea a 502
 * en GlobalExceptionHandler: que el proveedor externo se caiga no es un error de esta
 * app, y sin esta excepción caería en el handler genérico de 500.
 */
public class ImageKitException extends RuntimeException {

    public ImageKitException(String message) {
        super(message);
    }

    public ImageKitException(String message, Throwable cause) {
        super(message, cause);
    }
}
