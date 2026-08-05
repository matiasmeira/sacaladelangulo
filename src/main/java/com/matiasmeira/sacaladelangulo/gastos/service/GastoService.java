package com.matiasmeira.sacaladelangulo.gastos.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoMapper;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoRequest;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoResponse;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Gestión de gastos (egresos) de un establecimiento — espejo de VentaService para el lado
 * de los ingresos. Solo el dueño real o un ADMIN puede operar sobre los gastos, nunca un
 * empleado.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GastoService {

    private final GastoRepository gastoRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final GastoMapper gastoMapper;
    private final TurnoCajaService turnoCajaService;
    private final RegistroAuditoriaService registroAuditoriaService;

    @Transactional
    public GastoResponse registrarGasto(Long establecimientoId, GastoRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        validarMonto(request.monto());

        Gasto gasto = Gasto.builder()
                .establecimiento(establecimiento)
                .fecha(request.fecha())
                .monto(request.monto())
                .categoria(request.categoria())
                .descripcion(request.descripcion())
                .metodoPago(request.metodoPago())
                .comprobanteUrl(request.comprobanteUrl())
                .usuarioRegistro(usuarioAutenticado)
                .isActive(true)
                .build();

        gasto = gastoRepository.save(gasto);
        log.info("Gasto registrado. Establecimiento: {}, Categoría: {}, Monto: {}", establecimientoId, gasto.getCategoria(), gasto.getMonto());

        turnoCajaService.registrarMovimientoSiCorresponde(
                establecimiento, TipoMovimientoCaja.EGRESO, OrigenMovimientoCaja.GASTO,
                gasto.getMetodoPago(), gasto.getMonto(), "Gasto: " + gasto.getDescripcion(),
                gasto.getId(), usuarioAutenticado);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimiento,
                AccionAuditoria.REGISTRAR_GASTO, gasto.getId(), "Gasto registrado: " + gasto.getDescripcion() + " por " + gasto.getMonto());

        return gastoMapper.mapToResponse(gasto);
    }

    @Transactional
    public GastoResponse editarGasto(Long establecimientoId, Long gastoId, GastoRequest request, String email) {
        Gasto gasto = gastoRepository.findByIdAndEstablecimientoId(gastoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Gasto no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(gasto.getEstablecimiento(), email);
        if (Boolean.FALSE.equals(gasto.getIsActive())) {
            throw new IllegalArgumentException("No se puede editar un gasto eliminado");
        }
        validarMonto(request.monto());

        BigDecimal montoAnterior = gasto.getMonto();
        MetodoPago metodoPagoAnterior = gasto.getMetodoPago();

        gasto.setFecha(request.fecha());
        gasto.setMonto(request.monto());
        gasto.setCategoria(request.categoria());
        gasto.setDescripcion(request.descripcion());
        gasto.setMetodoPago(request.metodoPago());
        gasto.setComprobanteUrl(request.comprobanteUrl());

        Gasto gastoActualizado = gastoRepository.save(gasto);

        // Si cambió el monto o el método de pago, revierte el egreso original (si
        // correspondía) y registra el nuevo, para que el arqueo de caja no quede
        // desfasado tras editar un gasto ya contabilizado (ver M-04 en la auditoría).
        // registrarMovimientoSiCorresponde ya es un no-op si el método no era EFECTIVO o
        // no hay turno abierto, así que no hace falta duplicar esa condición acá.
        boolean montoOMetodoCambiaron = montoAnterior.compareTo(request.monto()) != 0 || metodoPagoAnterior != request.metodoPago();
        if (montoOMetodoCambiaron) {
            turnoCajaService.registrarMovimientoSiCorresponde(
                    gasto.getEstablecimiento(), TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.GASTO,
                    metodoPagoAnterior, montoAnterior,
                    "Ajuste por edición del gasto #" + gastoId + ": reversión del monto anterior",
                    gastoId, usuarioAutenticado);
            turnoCajaService.registrarMovimientoSiCorresponde(
                    gasto.getEstablecimiento(), TipoMovimientoCaja.EGRESO, OrigenMovimientoCaja.GASTO,
                    gasto.getMetodoPago(), gasto.getMonto(),
                    "Gasto editado: " + gasto.getDescripcion(), gastoId, usuarioAutenticado);
        }

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, gasto.getEstablecimiento(),
                AccionAuditoria.EDITAR_GASTO, gastoId, "Gasto editado: " + gasto.getDescripcion() + " por " + gasto.getMonto());

        return gastoMapper.mapToResponse(gastoActualizado);
    }

    /**
     * Anulación lógica (ver M-04 en la auditoría): antes hacía un DELETE físico, perdiendo
     * el historial financiero. Ahora marca isActive=false — la fila persiste para
     * auditoría, pero queda excluida del listado y de los reportes (ver GastoRepository).
     * Idempotente: si ya estaba eliminado, no vuelve a revertir el movimiento de caja.
     */
    @Transactional
    public void eliminarGasto(Long establecimientoId, Long gastoId, String email) {
        Gasto gasto = gastoRepository.findByIdAndEstablecimientoId(gastoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Gasto no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(gasto.getEstablecimiento(), email);

        if (Boolean.FALSE.equals(gasto.getIsActive())) {
            log.info("Gasto ya se encontraba eliminado. ID: {}", gastoId);
            return;
        }

        // Revierte el egreso original (si correspondía) antes de anular el gasto, para que
        // el arqueo de caja no quede desfasado (ver M-04 en la auditoría).
        turnoCajaService.registrarMovimientoSiCorresponde(
                gasto.getEstablecimiento(), TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.GASTO,
                gasto.getMetodoPago(), gasto.getMonto(),
                "Gasto eliminado: reversión de " + gasto.getDescripcion(), gastoId, usuarioAutenticado);

        gasto.setIsActive(false);
        gastoRepository.save(gasto);
        log.info("Gasto eliminado (anulación lógica). Establecimiento: {}, Gasto: {}", establecimientoId, gastoId);

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, gasto.getEstablecimiento(),
                AccionAuditoria.ELIMINAR_GASTO, gastoId, "Gasto eliminado: " + gasto.getDescripcion() + " por " + gasto.getMonto());
    }

    @Transactional(readOnly = true)
    public Page<GastoResponse> listarGastos(Long establecimientoId, String email, LocalDate desde, LocalDate hasta,
                                             CategoriaGasto categoria, Pageable pageable) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return gastoRepository.buscar(establecimientoId, desde, hasta, categoria, pageable)
                .map(gastoMapper::mapToResponse);
    }

    private void validarMonto(BigDecimal monto) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0");
        }
    }
}
