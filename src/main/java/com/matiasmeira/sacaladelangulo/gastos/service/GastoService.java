package com.matiasmeira.sacaladelangulo.gastos.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
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
                .build();

        gasto = gastoRepository.save(gasto);
        log.info("Gasto registrado. Establecimiento: {}, Categoría: {}, Monto: {}", establecimientoId, gasto.getCategoria(), gasto.getMonto());

        turnoCajaService.registrarMovimientoSiCorresponde(
                establecimiento, TipoMovimientoCaja.EGRESO, OrigenMovimientoCaja.GASTO,
                gasto.getMetodoPago(), gasto.getMonto(), "Gasto: " + gasto.getDescripcion(),
                gasto.getId(), usuarioAutenticado);

        return gastoMapper.mapToResponse(gasto);
    }

    @Transactional
    public GastoResponse editarGasto(Long establecimientoId, Long gastoId, GastoRequest request, String email) {
        Gasto gasto = gastoRepository.findByIdAndEstablecimientoId(gastoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Gasto no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(gasto.getEstablecimiento(), email);
        validarMonto(request.monto());

        gasto.setFecha(request.fecha());
        gasto.setMonto(request.monto());
        gasto.setCategoria(request.categoria());
        gasto.setDescripcion(request.descripcion());
        gasto.setMetodoPago(request.metodoPago());
        gasto.setComprobanteUrl(request.comprobanteUrl());

        return gastoMapper.mapToResponse(gastoRepository.save(gasto));
    }

    @Transactional
    public void eliminarGasto(Long establecimientoId, Long gastoId, String email) {
        Gasto gasto = gastoRepository.findByIdAndEstablecimientoId(gastoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Gasto no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(gasto.getEstablecimiento(), email);

        gastoRepository.delete(gasto);
        log.info("Gasto eliminado. Establecimiento: {}, Gasto: {}", establecimientoId, gastoId);
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
