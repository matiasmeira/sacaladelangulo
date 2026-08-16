package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Se publica al confirmar la baja de una cuenta (self o admin), con el email y el
 * nombre reales capturados ANTES de anonimizar (valores planos, no una referencia a la
 * entidad ya anonimizada), para el mail de confirmación (ver CuentaEliminadaEmailListener).
 */
public record CuentaEliminadaEvent(String email, String nombre) {
}
