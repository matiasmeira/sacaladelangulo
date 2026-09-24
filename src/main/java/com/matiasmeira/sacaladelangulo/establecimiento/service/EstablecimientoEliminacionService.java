package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Baja lógica (soft delete) de un establecimiento: deletedAt = now + slug liberado, sin
 * cascada -- canchas, empleados, turnos fijos, reservas históricas, cajas, ventas y gastos
 * quedan intactos, sólo se filtran a nivel establecimiento en cada consulta que ya los
 * excluye (ver EstablecimientoOperativoGuard, AutorizacionEmpleadoService, y las queries de
 * EstablecimientoRepository).
 *
 * <p>Es irreversible desde el punto de vista del producto: no hay endpoint de restauración
 * (si hiciera falta deshacerla, es un UPDATE a mano en la base). Por eso las dos
 * precondiciones se validan ANTES de tocar nada, y por eso el mail de confirmación al dueño
 * (ver EstablecimientoEliminadoEvent) es más enfático que el de deshabilitar.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstablecimientoEliminacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ComplejoDetalleCache complejoDetalleCache;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Solo el dueño real (validarPropietario, no validarPropietarioOAdmin): un ADMIN no
     * puede eliminar el establecimiento de otro, mismo criterio que
     * EstablecimientoEstadoService/EstablecimientoVerificacionService.
     */
    public void eliminarEstablecimiento(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario dueno = autorizacionEmpleadoService.validarPropietario(establecimiento, email);

        if (Boolean.TRUE.equals(establecimiento.getIsActive())) {
            throw new IllegalArgumentException(
                    "Este establecimiento está habilitado. Deshabilitalo primero desde /estado antes de eliminarlo.");
        }

        LocalDateTime ahora = LocalDateTime.now();
        List<Object[]> resumen = reservaRepository.resumenReservasFuturasConfirmadas(establecimientoId, ahora);
        long cantidadReservasFuturas = (long) resumen.get(0)[0];
        if (cantidadReservasFuturas > 0) {
            LocalDateTime fechaMasLejana = (LocalDateTime) resumen.get(0)[1];
            throw new IllegalArgumentException(
                    "Este establecimiento tiene " + cantidadReservasFuturas
                            + " reserva(s) futura(s) confirmada(s), la última el "
                            + fechaMasLejana.toLocalDate() + " a las " + fechaMasLejana.toLocalTime()
                            + ". Cancelalas desde la agenda antes de eliminar el establecimiento.");
        }

        String nombreOriginal = establecimiento.getNombre();
        String slugOriginal = establecimiento.getSlug();

        // Se invalida por el slug ORIGINAL, capturado antes de renombrar: invalidarPorEstablecimientoId
        // resolvería el slug NUEVO si se llamara después de guardar (re-lee la entidad por id),
        // y la ficha pública sigue cacheada bajo el slug viejo hasta ese momento.
        complejoDetalleCache.invalidarPorSlug(slugOriginal);

        establecimiento.setDeletedAt(ahora);
        establecimiento.setSlug(slugOriginal + "-eliminado-" + establecimiento.getId());
        establecimientoRepository.save(establecimiento);

        registroAuditoriaService.registrarSobreEstablecimiento(dueno, establecimiento,
                AccionAuditoria.ELIMINAR_ESTABLECIMIENTO, establecimiento.getId(),
                "Establecimiento eliminado: " + nombreOriginal + " (slug original: " + slugOriginal + ")");

        eventPublisher.publishEvent(new EstablecimientoEliminadoEvent(dueno.getEmail(), dueno.getNombre(), nombreOriginal));
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
