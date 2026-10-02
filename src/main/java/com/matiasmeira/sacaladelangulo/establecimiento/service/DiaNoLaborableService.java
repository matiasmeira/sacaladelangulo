package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiaNoLaborableService {

    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @Transactional
    public DiaNoLaborableResponse crear(Long establecimientoId, DiaNoLaborableRequest request, String email) {
        Establecimiento establecimiento = autorizarSobreEstablecimiento(establecimientoId, email);
        establecimientoOperativoGuard.validarPuedeGenerarCompromisosNuevos(establecimiento);

        if (diaNoLaborableRepository.existsByEstablecimientoIdAndFecha(establecimientoId, request.fecha())) {
            throw new IllegalArgumentException("Ya existe un día no laborable cargado para el " + request.fecha());
        }

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .establecimiento(establecimiento)
                .fecha(request.fecha())
                .motivo(request.motivo())
                .build();

        DiaNoLaborable guardado = diaNoLaborableRepository.save(diaNoLaborable);
        log.info("Día no laborable creado para el establecimiento {}. Fecha: {}", establecimientoId, request.fecha());

        return mapToResponse(guardado);
    }

    @Transactional
    public void eliminar(Long establecimientoId, Long diaNoLaborableId, String email) {
        autorizarSobreEstablecimiento(establecimientoId, email);

        // Acotado al establecimiento del path: inexistente o de otro complejo, el mismo 404.
        DiaNoLaborable diaNoLaborable = diaNoLaborableRepository.findById(diaNoLaborableId)
                .filter(dia -> dia.getEstablecimiento().getId().equals(establecimientoId))
                .orElseThrow(() -> new EntityNotFoundException("Día no laborable no encontrado"));

        diaNoLaborableRepository.delete(diaNoLaborable);
        log.info("Día no laborable {} eliminado del establecimiento {}", diaNoLaborableId, establecimientoId);
    }

    @Transactional(readOnly = true)
    public List<DiaNoLaborableResponse> listar(Long establecimientoId, String email) {
        autorizarSobreEstablecimiento(establecimientoId, email);

        return diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    private DiaNoLaborableResponse mapToResponse(DiaNoLaborable diaNoLaborable) {
        return new DiaNoLaborableResponse(diaNoLaborable.getId(), diaNoLaborable.getFecha(), diaNoLaborable.getMotivo());
    }

    /**
     * Autoriza contra el establecimiento del path antes de buscar o validar nada: un establecimiento
     * inexistente responde igual que uno ajeno (ver EstablecimientoAutorizado).
     */
    private Establecimiento autorizarSobreEstablecimiento(Long establecimientoId, String email) {
        return EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(establecimientoId),
                establecimiento -> autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email));
    }

}
