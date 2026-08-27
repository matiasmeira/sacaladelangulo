package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invalidación de la ficha pública de complejos (ComplejoPublicoService#obtenerDetalle).
 *
 * <p>La ficha se cachea por slug, pero todas las escrituras que la afectan llegan por
 * establecimientoId (PUT /api/v1/establecimientos/{id} y sus sub-recursos), así que hace
 * falta resolver id -> slug para saber qué entrada desalojar. El slug se genera una sola vez
 * en EstablecimientoService#crearEstablecimiento y actualizarEstablecimiento no lo regenera,
 * de modo que el mapeo es estable y alcanza con leerlo.
 *
 * <p><b>Por qué se desaloja después del commit y no con un @CacheEvict común.</b> Un
 * @CacheEvict (incluso con beforeInvocation=false) corre cuando retorna el método anotado,
 * que sigue estando <i>dentro</i> de la transacción. En la ventana entre ese desalojo y el
 * commit, cualquier lector concurrente encuentra la caché vacía, lee la fila todavía sin
 * commitear (o sea, la vieja) y la vuelve a guardar. Cuando el commit finalmente aterriza,
 * la caché ya quedó repoblada con el valor anterior y esa entrada envenenada sobrevive hasta
 * que expire el TTL. Registrando el desalojo en afterCommit, la caché se vacía recién cuando
 * el dato nuevo ya es visible para todos: el peor caso pasa a ser un miss de más, nunca un
 * dato viejo.
 *
 * <p>Se usa afterCommit y no afterCompletion a propósito: si la transacción hace rollback no
 * cambió nada en la base y lo que está cacheado sigue siendo correcto.
 *
 * <p>El slug se resuelve de forma ansiosa (antes de registrar el callback) porque en
 * afterCommit el contexto de persistencia ya está cerrado y una lectura lazy ahí explotaría.
 */
@Component
@RequiredArgsConstructor
public class ComplejoDetalleCache {

    public static final String CACHE_FICHA = "complejoDetalle";

    private final CacheManager cacheManager;
    private final EstablecimientoRepository establecimientoRepository;

    /**
     * Invalida la ficha del establecimiento indicado. Si el id no existe no hay nada que
     * desalojar (por ejemplo, una escritura que va a fallar de todas formas).
     */
    public void invalidarPorEstablecimientoId(Long establecimientoId) {
        if (establecimientoId == null) {
            return;
        }
        establecimientoRepository.findById(establecimientoId)
                .map(Establecimiento::getSlug)
                .ifPresent(this::invalidarPorSlug);
    }

    /**
     * Sin transacción activa (por ejemplo un llamado desde un test o un job suelto) no hay
     * commit que esperar y el desalojo es inmediato.
     */
    public void invalidarPorSlug(String slug) {
        if (slug == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            desalojar(slug);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                desalojar(slug);
            }
        });
    }

    private void desalojar(String slug) {
        Cache cache = cacheManager.getCache(CACHE_FICHA);
        if (cache != null) {
            cache.evict(slug);
        }
    }
}
