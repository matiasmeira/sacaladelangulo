package com.matiasmeira.sacaladelangulo.core.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de intentos en memoria (por instancia), basado en balde de tokens
 * por clave. Suficiente para una única instancia de la aplicación; si en el
 * futuro se despliega detrás de un balanceador con múltiples instancias, esto
 * debería reemplazarse por un almacenamiento compartido (ej. Redis).
 */
@Slf4j
@Service
public class RateLimiterService {

    private static final long UMBRAL_INACTIVIDAD_LIMPIEZA_MILLIS = 30 * 60 * 1000L;

    private final ConcurrentHashMap<String, TokenBucket> baldesPorClave = new ConcurrentHashMap<>();

    /**
     * Intenta consumir un intento para la clave indicada.
     *
     * @param clave           identificador del límite (ej. "login:IP:email")
     * @param capacidad       cantidad máxima de intentos permitidos en la ventana
     * @param ventanaMillis   duración de la ventana de recarga completa, en milisegundos
     * @return true si había un intento disponible y fue consumido; false si se agotaron
     */
    public boolean tryConsume(String clave, int capacidad, long ventanaMillis) {
        TokenBucket balde = baldesPorClave.computeIfAbsent(clave, k -> new TokenBucket(capacidad, ventanaMillis));
        boolean consumido = balde.tryConsume();
        if (!consumido) {
            log.warn("Rate limit excedido para la clave: {}", clave);
        }
        return consumido;
    }

    /**
     * Limpia baldes que no recibieron actividad reciente para no acumular memoria
     * indefinidamente (ej. IPs o usuarios que ya no vuelven a intentar).
     */
    @Scheduled(fixedRate = UMBRAL_INACTIVIDAD_LIMPIEZA_MILLIS)
    void limpiarBaldesInactivos() {
        long ahora = System.currentTimeMillis();
        int tamanioPrevio = baldesPorClave.size();
        baldesPorClave.entrySet().removeIf(entry -> entry.getValue().estaInactivo(ahora, UMBRAL_INACTIVIDAD_LIMPIEZA_MILLIS));
        int eliminados = tamanioPrevio - baldesPorClave.size();
        if (eliminados > 0) {
            log.debug("Limpieza de rate limiter: {} baldes inactivos eliminados", eliminados);
        }
    }
}
