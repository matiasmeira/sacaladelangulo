package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Factory de {@link Cancha} para tests, mismo motivo que {@link Establecimientos}: construirla
 * a mano con {@code Cancha.builder()} en cada archivo se vuelve insostenible apenas se suma un
 * campo nuevo a la entidad (y Cancha tiene más superficie todavía que Establecimiento: pool de
 * físicas, tarifas, duraciones permitidas, precios por duración).
 *
 * <p><b>A diferencia de {@link Establecimientos}, el {@link Establecimiento} es un parámetro
 * obligatorio</b>, no un valor de relleno que la factory se invente: una Cancha siempre
 * pertenece a un establecimiento concreto, y en un dominio multi-tenant esconder ese dato
 * detrás de un default es exactamente el tipo de atajo que hace pasar un test de aislamiento
 * por la razón equivocada (dos canchas "sueltas" terminando en el mismo establecimiento
 * inventado sin que el test lo haya pedido).
 *
 * <p><b>Defaults deliberados:</b> el caso común ({@link #canchaActiva}) es una cancha simple y
 * usable en un test de reserva de punta a punta -- sin pool (no es física de ninguna otra
 * lógica), con las duraciones mínimas que exige un alta real (60/90/120, mismo default que
 * {@code CanchaService.DURACIONES_POR_DEFECTO}) y sin tarifas variables. Un test al que le
 * importa el pool o las tarifas las suma con la personalización.
 *
 * <p><b>Mantenimiento:</b> si el día de mañana se suma un campo nuevo a Cancha, hay que
 * actualizar el default acá para que siga representando el caso común -- mismo aviso que
 * {@link Establecimientos}.
 */
public final class Canchas {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    private Canchas() {
    }

    /** Activa: el caso común, indiferente a la mayoría de los tests. */
    public static Cancha canchaActiva(Establecimiento establecimiento) {
        return canchaActiva(establecimiento, UnaryOperator.identity());
    }

    public static Cancha canchaActiva(Establecimiento establecimiento, UnaryOperator<Cancha.CanchaBuilder> personalizacion) {
        return construir(establecimiento, true, personalizacion);
    }

    /** Desactivada (isActive=false), pero NO eliminada: reversible, ver CanchaService.actualizarCancha. */
    public static Cancha canchaDesactivada(Establecimiento establecimiento) {
        return canchaDesactivada(establecimiento, UnaryOperator.identity());
    }

    public static Cancha canchaDesactivada(Establecimiento establecimiento, UnaryOperator<Cancha.CanchaBuilder> personalizacion) {
        return construir(establecimiento, false, personalizacion);
    }

    /**
     * Eliminada (deletedAt seteado): bajo el invariante de CanchaEliminacionService, siempre
     * nace también desactivada (isActive=false) -- no existe la combinación "eliminada pero
     * activa".
     */
    public static Cancha canchaEliminada(Establecimiento establecimiento) {
        return canchaEliminada(establecimiento, UnaryOperator.identity());
    }

    public static Cancha canchaEliminada(Establecimiento establecimiento, UnaryOperator<Cancha.CanchaBuilder> personalizacion) {
        return construir(establecimiento, false,
                b -> personalizacion.apply(b).deletedAt(LocalDateTime.now()));
    }

    private static Cancha construir(Establecimiento establecimiento, boolean activa,
                                     UnaryOperator<Cancha.CanchaBuilder> personalizacion) {
        int n = SECUENCIA.incrementAndGet();
        Cancha.CanchaBuilder builder = Cancha.builder()
                .nombre("Cancha Test " + n)
                .deportes(new LinkedHashSet<>(Set.of(Deporte.PADEL)))
                .isActive(activa)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .duracionesPermitidas(new ArrayList<>(Set.of(60, 90, 120).stream().sorted().toList()))
                .canchasFisicas(new LinkedHashSet<>())
                .tarifas(new ArrayList<>())
                .permiteInicioMediaHora(true);
        return personalizacion.apply(builder).build();
    }
}
