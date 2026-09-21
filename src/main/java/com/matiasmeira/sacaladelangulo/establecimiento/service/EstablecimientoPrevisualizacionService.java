package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PrevisualizacionEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoPublicoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Previsualización de la ficha pública de un establecimiento, para el dueño (mientras
 * espera o corrige su verificación) y para el ADMIN (para ver cómo quedaría antes de
 * aprobarla). Reusa ComplejoPublicoService#construirDetalle -- el mismo armado de DTO que
 * ve el público -- para que la previsualización sea fiel, pero resuelve el establecimiento
 * por id (no por slug ni por findBySlugOperativo) y sin pasar por la caché pública, así que
 * funciona sin importar isActive ni estadoVerificacion.
 *
 * <p>CRÍTICO: este servicio solo arma una vista de lectura. No cambia ningún estado del
 * establecimiento ni habilita reservar -- EstablecimientoOperativoGuard sigue rechazando
 * cualquier intento real de reserva sin importar cuántas veces se haya previsualizado.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EstablecimientoPrevisualizacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ComplejoPublicoService complejoPublicoService;

    public PrevisualizacionEstablecimientoResponse previsualizar(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        ComplejoDetalleResponse detalle = complejoPublicoService.construirDetalle(establecimiento);
        return new PrevisualizacionEstablecimientoResponse(detalle, establecimiento.getEstadoVerificacion(), true);
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
