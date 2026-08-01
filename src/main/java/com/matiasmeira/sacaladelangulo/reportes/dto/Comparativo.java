package com.matiasmeira.sacaladelangulo.reportes.dto;

/**
 * Envuelve una métrica junto con su valor en el período inmediatamente anterior de igual
 * duración, para que el front pueda mostrar la comparación sin pedirla aparte.
 */
public record Comparativo<T>(T actual, T anterior) {
}
