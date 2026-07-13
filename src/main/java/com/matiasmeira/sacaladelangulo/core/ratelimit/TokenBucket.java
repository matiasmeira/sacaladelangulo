package com.matiasmeira.sacaladelangulo.core.ratelimit;

/**
 * Balde de tokens thread-safe: se recargan linealmente con el tiempo hasta la
 * capacidad máxima. Cada intento consume 1 token; si no hay tokens disponibles,
 * el intento se rechaza.
 */
class TokenBucket {

    private final int capacidad;
    private final double tokensPorMilisegundo;
    private double tokensDisponibles;
    private long ultimaRecarga;

    TokenBucket(int capacidad, long ventanaMillis) {
        this.capacidad = capacidad;
        this.tokensPorMilisegundo = (double) capacidad / ventanaMillis;
        this.tokensDisponibles = capacidad;
        this.ultimaRecarga = System.currentTimeMillis();
    }

    synchronized boolean tryConsume() {
        recargar();
        if (tokensDisponibles < 1) {
            return false;
        }
        tokensDisponibles -= 1;
        return true;
    }

    synchronized boolean estaInactivo(long ahora, long umbralInactividadMillis) {
        return (ahora - ultimaRecarga) > umbralInactividadMillis;
    }

    private void recargar() {
        long ahora = System.currentTimeMillis();
        long transcurrido = ahora - ultimaRecarga;
        if (transcurrido <= 0) {
            return;
        }
        tokensDisponibles = Math.min(capacidad, tokensDisponibles + transcurrido * tokensPorMilisegundo);
        ultimaRecarga = ahora;
    }
}
