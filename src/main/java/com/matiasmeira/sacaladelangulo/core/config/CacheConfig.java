package com.matiasmeira.sacaladelangulo.core.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Habilita el soporte de @Cacheable/@CacheEvict, usado hoy sólo por la ficha pública de
 * complejos (ver ComplejoDetalleCache): es la lectura anónima más caliente del marketplace
 * y la que más se repite entre visitantes distintos.
 *
 * <p>El backend es Caffeine, configurado por properties (spring.cache.caffeine.spec) y no
 * acá, porque no hace falta ningún bean propio: alcanza con tener caffeine en el classpath.
 * Se eligió sobre ConcurrentMapCacheManager (el default sin dependencias) porque ese no
 * tiene TTL ni tamaño máximo: es un ConcurrentHashMap que crece sin techo y cuyas entradas
 * viven hasta que alguien las desaloja a mano. Con TTL, una invalidación que se nos escape
 * degrada a "datos viejos por 5 minutos" en vez de "datos viejos para siempre".
 *
 * <p>Caffeine es in-process: con varias instancias, cada una tiene su propia copia y su
 * propia ventana de expiración. Para invalidación consistente entre instancias haría falta
 * un backend compartido (Redis), que hoy el proyecto no tiene.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
