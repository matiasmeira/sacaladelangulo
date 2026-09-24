package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import org.springframework.security.access.AccessDeniedException;
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

    private boolean estaEliminado(Establecimiento establecimiento) {
        return establecimiento.getDeletedAt() != null;
    }

    private boolean estaOperativo(Establecimiento establecimiento) {
        return estaActivo(establecimiento) && estaVerificado(establecimiento) && !estaEliminado(establecimiento);
    }

    /**
     * Camino del jugador (crearReserva, disponibilidad pública y disponibilidad por id):
     * "Establecimiento no encontrado", el mismo mensaje y criterio que
     * findBySlugOperativo, findActivosPorDeporte, findCercanosYPorDeporte (ver
     * EstablecimientoOperativoCoherenciaTest, que ata las dos capas) y que
     * validarCanchaActivaParaJugador a nivel cancha. A
     * propósito no revela si el establecimiento existe pero está deshabilitado, no
     * verificado, eliminado, o cualquier combinación de las tres cosas.
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
     *
     * <p>Un establecimiento eliminado también cae en la rama de "deshabilitado": bajo el
     * invariante que mantiene EstablecimientoEliminacionService (sólo se puede eliminar un
     * establecimiento ya deshabilitado), deletedAt != null siempre implica isActive = false,
     * así que nunca hace falta un mensaje aparte para "eliminado" acá -- y este camino de
     * todos modos no debería ser alcanzable para un establecimiento eliminado, porque ya no
     * aparece en el panel del dueño para que se lo pueda ni intentar.
     */
    public void validarEstablecimientoOperativoParaPanel(Establecimiento establecimiento) {
        if (!estaActivo(establecimiento) || estaEliminado(establecimiento)) {
            throw new IllegalArgumentException(
                    "Este establecimiento está deshabilitado. No se pueden cargar reservas nuevas mientras esté así.");
        }
        if (!estaVerificado(establecimiento)) {
            throw new IllegalArgumentException(
                    "Este establecimiento todavía no está verificado. No se pueden cargar reservas nuevas hasta que se apruebe la verificación.");
        }
    }

    /**
     * Camino de ALTAS y operaciones nuevas del panel (dueño/admin/empleado) que generan un
     * compromiso nuevo de cara al público -- una venta, un turno de caja, un producto, una
     * cancha, un dispositivo de caja, un bloqueo, un día no laborable, una foto -- sobre un
     * establecimiento deshabilitado (isActive = false). Administrar o leer lo que YA existe
     * (cancelar/finalizar una reserva, cerrar un turno de caja ya abierto, editar/eliminar/
     * listar cualquier cosa, dar de alta un empleado, cambiar el propio estado habilitado/
     * deshabilitado) queda EXENTO a propósito: deshabilitar un establecimiento no es darlo de
     * baja, es pausar la exposición al público, y el dueño tiene que poder seguir
     * administrando lo que ya tiene mientras decide qué hacer.
     *
     * <p>A diferencia de {@link #validarEstablecimientoOperativoParaJugador} y
     * {@link #validarEstablecimientoOperativoParaPanel}, este método NO es parte del criterio
     * "operativo" (isActive Y estadoVerificacion Y deletedAt): sólo mira isActive y deletedAt,
     * NO estadoVerificacion. Un establecimiento todavía no verificado tiene que poder seguir
     * dando de alta canchas, productos y fotos como parte de su propio onboarding -- exigir
     * también estadoVerificacion acá se lo impediría. deletedAt sí se suma (a diferencia de
     * cuando se escribió este método por primera vez): un establecimiento eliminado tampoco
     * puede generar compromisos nuevos, aunque en la práctica esto es defensivo -- bajo el
     * invariante de EstablecimientoEliminacionService (sólo se elimina lo ya deshabilitado),
     * el chequeo de isActive ya lo cubre.
     *
     * <p><b>NO es automático.</b> A diferencia de un filtro de query o un @Where de Hibernate,
     * este método no intercepta nada por sí solo: hay que invocarlo a mano en cada punto de
     * alta nuevo que se agregue al panel, igual que ya se hace en los de abajo. Si mañana se
     * agrega un nuevo tipo de alta (una promoción, un nuevo tipo de recurso), quien lo escriba
     * tiene que acordarse de sumar la llamada -- no hay red de contención. Puntos donde se
     * invoca hoy:
     * <ul>
     *   <li>{@code TurnoCajaService.abrirCaja}
     *   <li>{@code VentaService.registrarVenta}
     *   <li>{@code ProductoBuffetService.crearProducto}
     *   <li>{@code DispositivoCajaService.activarLocal}, {@code .emparejar}
     *   <li>{@code BloqueoCanchaService.crearBloqueo}
     *   <li>{@code BloqueoJugadorService.crearBloqueo}
     *   <li>{@code DiaNoLaborableService.crear}
     *   <li>{@code FotoEstablecimientoService.subir}
     *   <li>{@code GastoService.registrarGasto}
     *   <li>{@code CanchaService.crearCancha}
     * </ul>
     * Crear una reserva o un turno fijo YA estaba cubierto antes de que existiera este
     * método, por {@link #validarEstablecimientoOperativoParaPanel} en
     * {@code ReservaService.crearReserva}/{@code crearReservaManual} y
     * {@code TurnoFijoService.crearInterno} -- éste último incluye {@code renovar}, que
     * delega en {@code crearInterno} y hereda el chequeo. No se duplica acá.
     *
     * <p><b>Deuda anotada, no accidente para perseguir ahora:</b> tres superficies NO pasan
     * por acá y quedan exentas por el momento, pero no porque alguien haya decidido
     * activamente que un establecimiento deshabilitado deba seguir operándolas -- simplemente
     * no llegan a este chequeo:
     * <ul>
     *   <li>{@code ReporteAutorizacionService} (reportes de ocupación, horarios, gastos,
     *   facturación, clientes, cierre de caja): reimplementación paralela e independiente del
     *   chequeo dueño-o-admin, desconectada de {@code AutorizacionEmpleadoService}. Da la
     *   respuesta que se quiere (reportes históricos siguen disponibles), pero por omisión,
     *   no por diseño.
     *   <li>{@code ReservaService.confirmarReserva}: chequeo manual propio (rol ADMIN o
     *   dueño), no pasa por {@code AutorizacionEmpleadoService} ni por acá.
     *   <li>{@code ReservaService.cancelarReserva}: llama a
     *   {@code AutorizacionEmpleadoService.tienePermiso} directo, combinado a mano con el
     *   chequeo de dueño/admin/jugador -- no pasa por {@code validarAccion} ni por acá.
     * </ul>
     * El día que el criterio de "administrar lo existente" cambie para alguna de estas tres,
     * son los primeros lugares que se van a haber olvidado.
     */
    public void validarPuedeGenerarCompromisosNuevos(Establecimiento establecimiento) {
        if (!estaActivo(establecimiento) || estaEliminado(establecimiento)) {
            throw new AccessDeniedException(
                    "Este establecimiento está deshabilitado. No se pueden generar compromisos nuevos "
                            + "(reservas, ventas, altas) mientras esté así.");
        }
    }
}
