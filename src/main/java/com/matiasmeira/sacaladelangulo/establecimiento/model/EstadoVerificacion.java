package com.matiasmeira.sacaladelangulo.establecimiento.model;

/**
 * Enum que representa los estados posibles de la verificación manual de un establecimiento.
 */
public enum EstadoVerificacion {
    /**
     * Establecimiento recién creado; el dueño todavía no envió los datos de verificación.
     */
    PENDIENTE,

    /**
     * El dueño ya envió la solicitud de verificación; espera la decisión de un admin.
     */
    EN_REVISION,

    /**
     * Verificación aprobada por un admin.
     */
    VERIFICADO,

    /**
     * Verificación rechazada, con motivo (ver Establecimiento.motivoRechazo). Puede volver a
     * EN_REVISION si el dueño resolicita la verificación.
     */
    RECHAZADO
}
