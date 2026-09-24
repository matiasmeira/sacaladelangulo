package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Factory de {@link Establecimiento} para tests, centralizada porque construirlo a mano con
 * {@code Establecimiento.builder()} en cada archivo resultó no ser sostenible: con ~70 archivos
 * haciéndolo cada uno por su cuenta, cada campo nuevo que se agrega a la entidad rompe un
 * subconjunto impredecible de tests, y ese subconjunto sólo se manifiesta en la corrida completa
 * de la suite -- no al correr las clases sueltas. Pasó en concreto al agregar
 * {@code estadoVerificacion} (default {@code PENDIENTE}): 3 tests fallaron por depender de un
 * default que nadie había hecho explícito.
 *
 * <p><b>Por qué el default es "operativo" (VERIFICADO + isActive=true) y no el default "en blanco"
 * de la entidad (PENDIENTE):</b> es deliberado. La enorme mayoría de los tests que construyen un
 * Establecimiento no están probando el estado de verificación -- les es indiferente -- así que no
 * tienen por qué enterarse de que ese estado existe. Que el caso común sea el método sin
 * parámetros ({@link #establecimientoOperativo()}) es lo que permite eso. Los tests a los que SÍ
 * les importa un estado puntual (pendiente, en revisión, rechazado, deshabilitado) lo dicen con el
 * nombre del método, sin tener que leer el cuerpo para saber en qué estado quedó el objeto.
 *
 * <p><b>Excepción a propósito:</b> {@code EstablecimientoOperativoGuardTest} y
 * {@code EstablecimientoOperativoCoherenciaTest} NO usan esta factory. Esos dos tests existen
 * específicamente para fijar el criterio de "operativo" (isActive + estadoVerificacion); si
 * construyeran el Establecimiento con estos defaults estarían verificando la factory en vez del
 * guard real. Siguen construyendo cada combinación a mano con {@code Establecimiento.builder()}.
 *
 * <p><b>Mantenimiento:</b> si el día de mañana se suma una condición nueva al criterio de
 * "operativo" (hoy isActive + estadoVerificacion -- ver {@code EstablecimientoOperativoGuard}),
 * hay que actualizar el default de {@link #establecimientoOperativo()} acá para que siga
 * representando el caso común. Si no se actualiza, los tests que dependen del default vuelven a
 * quedar expuestos al mismo problema que motivó esta clase.
 */
public final class Establecimientos {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    private Establecimientos() {
    }

    /** Activo y verificado: el caso común, el que le es indiferente a la mayoría de los tests. */
    public static Establecimiento establecimientoOperativo() {
        return establecimientoOperativo(UnaryOperator.identity());
    }

    public static Establecimiento establecimientoOperativo(UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        return construir(EstadoVerificacion.VERIFICADO, true, personalizacion);
    }

    /** Recién creado, todavía sin pasar por verificación manual. */
    public static Establecimiento establecimientoPendiente() {
        return establecimientoPendiente(UnaryOperator.identity());
    }

    public static Establecimiento establecimientoPendiente(UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        return construir(EstadoVerificacion.PENDIENTE, true, personalizacion);
    }

    /** Solicitud de verificación enviada, pendiente de que un admin la resuelva. */
    public static Establecimiento establecimientoEnRevision() {
        return establecimientoEnRevision(UnaryOperator.identity());
    }

    public static Establecimiento establecimientoEnRevision(UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        return construir(EstadoVerificacion.EN_REVISION, true, personalizacion);
    }

    /** Verificación rechazada por un admin. */
    public static Establecimiento establecimientoRechazado() {
        return establecimientoRechazado(UnaryOperator.identity());
    }

    public static Establecimiento establecimientoRechazado(UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        return construir(EstadoVerificacion.RECHAZADO, true, personalizacion);
    }

    /** Verificado pero deshabilitado (isActive=false): dimensión independiente de la verificación. */
    public static Establecimiento establecimientoDeshabilitado() {
        return establecimientoDeshabilitado(UnaryOperator.identity());
    }

    public static Establecimiento establecimientoDeshabilitado(UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        return construir(EstadoVerificacion.VERIFICADO, false, personalizacion);
    }

    private static Establecimiento construir(EstadoVerificacion estadoVerificacion, boolean activo,
                                              UnaryOperator<Establecimiento.EstablecimientoBuilder> personalizacion) {
        int n = SECUENCIA.incrementAndGet();
        Establecimiento.EstablecimientoBuilder builder = Establecimiento.builder()
                .nombre("Complejo Test " + n)
                .direccion("Calle Test " + n)
                .slug("complejo-test-" + n)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(activo)
                .estadoVerificacion(estadoVerificacion)
                .dueno(duenoPorDefecto(n));
        return personalizacion.apply(builder).build();
    }

    /**
     * Dueño de relleno para cuando el test no provee uno propio vía {@code .dueno(...)} en la
     * personalización. Sirve tal cual para tests unitarios (nunca se persiste); los tests de
     * integración que necesitan un dueño realmente guardado en la base siguen persistiendo el
     * suyo y pasándolo con {@code establecimientoOperativo(b -> b.dueno(duenoPersistido))} --
     * esta factory no tiene acceso a ningún repositorio.
     */
    private static Usuario duenoPorDefecto(int n) {
        return Usuario.builder()
                .email("dueno-establecimiento-test-" + n + "@test.com")
                .password("hash")
                .nombre("Dueno Test " + n)
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();
    }
}
