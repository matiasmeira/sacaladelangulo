package com.matiasmeira.sacaladelangulo.auth.model;

/**
 * Distingue si una baja de cuenta la ejecutó el propio usuario o un ADMIN (ver
 * UsuarioEliminacionService).
 */
public enum TipoEliminacionCuenta {
    AUTOELIMINACION,
    ELIMINACION_ADMIN
}
