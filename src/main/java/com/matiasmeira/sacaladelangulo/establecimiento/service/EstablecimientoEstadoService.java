package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Habilitar/deshabilitar un establecimiento (isActive), del lado del dueño, sin eliminar
 * nada. No toca estadoVerificacion -- son dos ejes independientes (ver
 * EstablecimientoOperativoGuard): un establecimiento deshabilitado y luego rehabilitado no
 * vuelve a la cola de revisión, sigue con el estado de verificación que ya tenía.
 *
 * <p>Las reservas y turnos fijos existentes se respetan: nada se cancela acá.
 * EstablecimientoOperativoGuard ya garantiza esto (bloquea CREAR reservas nuevas, no
 * administrar las existentes); este servicio no duplica esa lógica, solo cambia isActive.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstablecimientoEstadoService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ComplejoDetalleCache complejoDetalleCache;
    private final ReservaRepository reservaRepository;

    public CambiarEstadoEstablecimientoResponse cambiarEstado(Long establecimientoId, CambiarEstadoEstablecimientoRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario dueno = autorizacionEmpleadoService.validarPropietario(establecimiento, email);

        establecimiento.setIsActive(request.activo());
        Establecimiento guardado = establecimientoRepository.save(establecimiento);

        // En la misma transacción que el UPDATE de arriba: ComplejoDetalleCache registra el
        // desalojo real para después del commit (ver su javadoc), tanto al deshabilitar como
        // al rehabilitar -- el detalle público tiene que reflejar el cambio en ambos
        // sentidos sin esperar el TTL.
        complejoDetalleCache.invalidarPorEstablecimientoId(guardado.getId());

        long reservasFuturasConfirmadas = reservaRepository.countReservasFuturasConfirmadas(guardado.getId(), LocalDateTime.now());

        registroAuditoriaService.registrarSobreEstablecimiento(dueno, guardado,
                request.activo() ? AccionAuditoria.HABILITAR_ESTABLECIMIENTO : AccionAuditoria.DESHABILITAR_ESTABLECIMIENTO,
                guardado.getId(),
                (request.activo() ? "Establecimiento habilitado: " : "Establecimiento deshabilitado: ") + guardado.getNombre());

        return new CambiarEstadoEstablecimientoResponse(
                guardado.getId(), guardado.getIsActive(), guardado.getEstadoVerificacion(), reservasFuturasConfirmadas);
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
