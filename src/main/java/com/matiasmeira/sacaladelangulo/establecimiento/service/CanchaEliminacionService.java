package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Baja lógica (soft delete) de una cancha: deletedAt = now, sin cascada -- tarifas, bloqueos,
 * reservas históricas y turnos fijos quedan intactos, sólo se filtran a nivel cancha en cada
 * consulta que ya los excluye (ver CanchaRepository.findByEstablecimientoId/In). Mismo patrón
 * que {@link EstablecimientoEliminacionService}.
 *
 * <p>Es irreversible desde el punto de vista del producto: no hay endpoint de restauración.
 * Por eso las dos precondiciones se validan ANTES de tocar nada. A diferencia de la
 * eliminación de un establecimiento, no dispara ningún email: es una acción menor dentro del
 * propio complejo, no una baja de negocio.
 *
 * <p><b>Por qué no hace falta una tercera precondición para turnos fijos:</b> un turno fijo
 * ACTIVO con ocurrencias futuras vigentes SIEMPRE tiene esas ocurrencias como filas Reserva en
 * estado CONFIRMADA -- TurnoFijoService.crearInterno las persiste todas de una sola vez, en la
 * misma transacción que la regla, para todo el período pedido (no hay materialización
 * diferida). Por eso la precondición de reservas futuras confirmadas (ver
 * {@link ReservaRepository#resumenReservasFuturasConfirmadasPorCancha}) ya encuentra y bloquea
 * también este caso, sin necesidad de consultar TurnoFijoRepository acá. Si el día de mañana
 * los turnos fijos empiezan a materializar ocurrencias de forma diferida, esta garantía deja
 * de valer y esta precondición hay que revisarla.
 *
 * <p><b>Por qué eliminar esta cancha no afecta el pool de ninguna otra:</b> la Precondición 1
 * exige que la cancha ya esté isActive=false antes de eliminarla, y
 * {@code PoolCanchaCalculator.footprint()} ya excluye las físicas con isActive=false de la
 * capacidad de cualquier grupo -- es un chequeo por isActive, no por deletedAt. Eliminarla no
 * cambia isActive (ya era false), así que su contribución al footprint de cualquier lógica que
 * la use como física ya estaba en cero desde que se desactivó, no desde que se elimina.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CanchaEliminacionService {

    private final CanchaRepository canchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ComplejoDetalleCache complejoDetalleCache;

    /**
     * Solo el dueño real (validarPropietario, no validarPropietarioOAdmin): un ADMIN no puede
     * eliminar la cancha de otro, mismo criterio que EstablecimientoEliminacionService.
     */
    public void eliminarCancha(Long establecimientoId, Long canchaId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario dueno = autorizacionEmpleadoService.validarPropietario(establecimiento, email);

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));
        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        if (Boolean.TRUE.equals(cancha.getIsActive())) {
            throw new IllegalArgumentException(
                    "Esta cancha está habilitada. Desactivala primero antes de eliminarla.");
        }

        LocalDateTime ahora = LocalDateTime.now();
        List<Object[]> resumen = reservaRepository.resumenReservasFuturasConfirmadasPorCancha(canchaId, ahora);
        long cantidadReservasFuturas = (long) resumen.get(0)[0];
        if (cantidadReservasFuturas > 0) {
            LocalDateTime fechaMasLejana = (LocalDateTime) resumen.get(0)[1];
            throw new IllegalArgumentException(
                    "Esta cancha tiene " + cantidadReservasFuturas
                            + " reserva(s) futura(s) confirmada(s), la última el "
                            + fechaMasLejana.toLocalDate() + " a las " + fechaMasLejana.toLocalTime()
                            + ". Cancelalas antes de eliminar la cancha.");
        }

        String nombreOriginal = cancha.getNombre();
        cancha.setDeletedAt(ahora);
        canchaRepository.save(cancha);

        registroAuditoriaService.registrarSobreEstablecimiento(dueno, establecimiento,
                AccionAuditoria.ELIMINAR_CANCHA, cancha.getId(),
                "Cancha eliminada: " + nombreOriginal);

        complejoDetalleCache.invalidarPorEstablecimientoId(establecimientoId);
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
