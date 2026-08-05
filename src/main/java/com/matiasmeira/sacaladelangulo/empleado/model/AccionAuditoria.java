package com.matiasmeira.sacaladelangulo.empleado.model;

/**
 * Acciones auditables sobre un establecimiento: tanto operaciones que un empleado
 * ejecuta con un permiso (mismos nombres que PermisoEmpleado, ver ReservaService/
 * VentaService) como acciones administrativas que el dueño/admin realiza sobre un
 * empleado (ver M31 en la auditoría). Separado de PermisoEmpleado a propósito: ese
 * enum es específicamente "permisos que se le pueden otorgar a un empleado"
 * (Usuario.permisos), y mezclarle acciones administrativas haría que aparezcan, por
 * error, como opción seleccionable al otorgar permisos.
 */
public enum AccionAuditoria {
    CREAR_RESERVA_MANUAL,
    FINALIZAR_RESERVA,
    CANCELAR_RESERVA,
    MARCAR_AUSENTE,
    REGISTRAR_VENTA_BUFFET,
    FIJAR_COMENTARIO_DESTACADO,

    CREAR_EMPLEADO,
    ACTUALIZAR_PERMISOS_EMPLEADO,
    CAMBIAR_PIN_EMPLEADO,
    DESACTIVAR_EMPLEADO,

    ACTIVAR_DISPOSITIVO_CAJA,
    EMPAREJAR_DISPOSITIVO_CAJA,
    REVOCAR_DISPOSITIVO_CAJA,

    ABRIR_CAJA,
    CERRAR_CAJA,
    REGISTRAR_MOVIMIENTO_CAJA,

    // Acciones administrativas de dinero del propio OWNER/ADMIN sobre su establecimiento
    // (ver §3 "Consistencia entre features" en la auditoría): antes solo se auditaba la
    // actividad de mostrador de los empleados, dejando sin rastro cambios de precio/tarifa
    // y altas/ediciones/bajas de gastos hechos directamente por el dueño.
    REGISTRAR_GASTO,
    EDITAR_GASTO,
    ELIMINAR_GASTO,
    CREAR_CANCHA,
    ACTUALIZAR_CANCHA
}
