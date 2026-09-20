package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import org.springframework.stereotype.Component;

/**
 * Punto único que responde una sola pregunta de negocio: ¿este establecimiento está en
 * condiciones de operar de cara al público (visible, y puede recibir reservas nuevas)?
 * Ningún caller de afuera lee Establecimiento.getIsActive() ni Establecimiento.getEstadoVerificacion()
 * (ni ningún otro campo de estado) directamente para decidir esto -- siempre pasan por acá.
 *
 * <p>El criterio de hoy es isActive = true Y estadoVerificacion = VERIFICADO. Son dos
 * condiciones independientes: ninguna enmascara a la otra, un establecimiento puede estar
 * deshabilitado y sin verificar al mismo tiempo. Este es un detalle de implementación, no la
 * definición: el criterio puede sumar condiciones a futuro ajustando únicamente esta clase,
 * sin tocar ningún call site ni su forma de invocarlo.
 *
 * <p>Expone dos métodos porque el repo ya trata distinto a quién le pregunta: al jugador un
 * establecimiento no operativo se le presenta como inexistente (404 opaco) -- idéntico se
 * trate de un establecimiento inexistente, inactivo, no verificado, o ambas cosas a la vez;
 * un tercero no debe poder distinguir esos casos. Al panel, en cambio, se le explica qué pasa
 * porque ya sabe de qué establecimiento se trata (400 explícito) y el mensaje SÍ distingue
 * "deshabilitado" de "no verificado", porque son dos acciones distintas que el dueño tiene
 * que resolver de formas distintas.
 */
@Component
public class EstablecimientoOperativoGuard {

    private boolean estaActivo(Establecimiento establecimiento) {
        return Boolean.TRUE.equals(establecimiento.getIsActive());
    }

    private boolean estaVerificado(Establecimiento establecimiento) {
        return establecimiento.getEstadoVerificacion() == EstadoVerificacion.VERIFICADO;
    }

    private boolean estaOperativo(Establecimiento establecimiento) {
        return estaActivo(establecimiento) && estaVerificado(establecimiento);
    }

    /**
     * Camino del jugador (crearReserva, disponibilidad pública y disponibilidad por id):
     * "Establecimiento no encontrado", el mismo mensaje y criterio que
     * findBySlugOperativo, findActivosPorDeporte, findCercanosYPorDeporte (ver
     * EstablecimientoOperativoCoherenciaTest, que ata las dos capas) y que
     * validarCanchaActivaParaJugador a nivel cancha. A
     * propósito no revela si el establecimiento existe pero está deshabilitado, no
     * verificado, o ambas cosas.
     */
    public void validarEstablecimientoOperativoParaJugador(Establecimiento establecimiento) {
        if (!estaOperativo(establecimiento)) {
            throw new EntityNotFoundException("Establecimiento no encontrado");
        }
    }

    /**
     * Caminos de panel (crearReservaManual, TurnoFijoService.crearInterno): el dueño/admin/
     * empleado ya sabe cuál es su establecimiento -- mensaje explícito sobre el
     * establecimiento, no sobre la cancha, con el mismo criterio que
     * validarCanchaActivaParaPanel. Si falla por las dos condiciones a la vez, se reporta
     * primero "deshabilitado" (isActive): es la acción más directa que el dueño puede tomar
     * (reactivarlo) y no depende de un admin, a diferencia de la verificación.
     */
    public void validarEstablecimientoOperativoParaPanel(Establecimiento establecimiento) {
        if (!estaActivo(establecimiento)) {
            throw new IllegalArgumentException(
                    "Este establecimiento está deshabilitado. No se pueden cargar reservas nuevas mientras esté así.");
        }
        if (!estaVerificado(establecimiento)) {
            throw new IllegalArgumentException(
                    "Este establecimiento todavía no está verificado. No se pueden cargar reservas nuevas hasta que se apruebe la verificación.");
        }
    }
}
