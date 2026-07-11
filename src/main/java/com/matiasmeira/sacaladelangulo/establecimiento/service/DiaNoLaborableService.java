package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DiaNoLaborableService {

    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public DiaNoLaborableResponse crear(Long establecimientoId, DiaNoLaborableRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        validarPropietarioOAdmin(establecimiento, email);

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
        DiaNoLaborable diaNoLaborable = diaNoLaborableRepository.findById(diaNoLaborableId)
                .orElseThrow(() -> new EntityNotFoundException("Día no laborable no encontrado"));

        if (!diaNoLaborable.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("El día no laborable no pertenece a este establecimiento");
        }

        validarPropietarioOAdmin(diaNoLaborable.getEstablecimiento(), email);

        diaNoLaborableRepository.delete(diaNoLaborable);
        log.info("Día no laborable {} eliminado del establecimiento {}", diaNoLaborableId, establecimientoId);
    }

    @Transactional(readOnly = true)
    public List<DiaNoLaborableResponse> listar(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        validarPropietarioOAdmin(establecimiento, email);

        return diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimientoId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    private DiaNoLaborableResponse mapToResponse(DiaNoLaborable diaNoLaborable) {
        return new DiaNoLaborableResponse(diaNoLaborable.getId(), diaNoLaborable.getFecha(), diaNoLaborable.getMotivo());
    }

    private Establecimiento buscarEstablecimiento(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    private void validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
    }
}
