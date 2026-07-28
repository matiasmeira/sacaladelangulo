package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para un jugador bloqueado por el dueño/admin de un establecimiento
 * (ver BloqueoJugador) que intenta reservar por sí mismo en ese establecimiento. Se mapea
 * a HTTP 403: es una restricción de autorización sobre una acción puntual, pero se modela
 * como excepción propia (en vez de reutilizar AccessDeniedException) para que el frontend
 * pueda distinguir "estás bloqueado" de "no sos dueño/admin de esto".
 */
public class JugadorBloqueadoException extends RuntimeException {

    public JugadorBloqueadoException(String message) {
        super(message);
    }
}
