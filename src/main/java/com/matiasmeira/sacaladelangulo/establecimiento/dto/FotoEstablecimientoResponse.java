package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * Una foto tal como la ve el panel del dueño. Expone el fileId (a diferencia de la zona
 * pública, que solo manda URLs) porque es la clave con la que el panel pide borrar y
 * reordenar.
 */
public record FotoEstablecimientoResponse(String url, String fileId) {
}
