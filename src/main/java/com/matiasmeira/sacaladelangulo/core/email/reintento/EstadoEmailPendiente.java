package com.matiasmeira.sacaladelangulo.core.email.reintento;

/**
 * Estados de una fila de la cola de reintento. No hay ENVIADO: cuando el envío sale bien
 * la fila se borra, así la tabla contiene sólo lo que todavía requiere atención y el job
 * no tiene que filtrar histórico en cada corrida.
 */
public enum EstadoEmailPendiente {

    /** Falló al menos una vez y todavía le quedan intentos. */
    PENDIENTE,

    /** Agotó los intentos. Ya no se reintenta solo: requiere intervención manual. */
    ERROR
}
