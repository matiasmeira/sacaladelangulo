package com.matiasmeira.sacaladelangulo.auth.model;

/**
 * Acciones puntuales que el dueño puede habilitar a un empleado (Usuario con
 * rol EMPLOYEE) sobre su establecimiento.
 */
public enum PermisoEmpleado {
    CREAR_RESERVA_MANUAL,
    FINALIZAR_RESERVA,
    CANCELAR_RESERVA,
    REGISTRAR_VENTA_BUFFET,
    FIJAR_COMENTARIO_DESTACADO
}
