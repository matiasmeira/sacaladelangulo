package com.matiasmeira.sacaladelangulo.core.imagekit;

/**
 * Resultado de subir un archivo a ImageKit: la URL pública con la que se sirve y el
 * fileId con el que después se lo borra. Los dos se persisten en FotoEstablecimiento.
 */
public record FotoSubida(String url, String fileId) {
}
