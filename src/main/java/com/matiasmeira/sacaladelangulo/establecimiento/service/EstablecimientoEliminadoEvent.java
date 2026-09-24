package com.matiasmeira.sacaladelangulo.establecimiento.service;

/**
 * Se publica al confirmar la eliminación de un establecimiento (ver
 * EstablecimientoEliminacionService), para el mail de confirmación al dueño (ver
 * EstablecimientoEliminadoEmailListener). A diferencia de CuentaEliminadaEvent, acá no hace
 * falta capturar el email del dueño "antes de anonimizar" -- eliminar un establecimiento no
 * toca la cuenta del dueño en absoluto, sólo el establecimiento.
 */
public record EstablecimientoEliminadoEvent(String emailDueno, String nombreDueno, String nombreEstablecimiento) {
}
