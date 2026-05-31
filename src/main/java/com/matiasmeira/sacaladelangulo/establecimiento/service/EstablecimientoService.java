package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para establecimientos.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstablecimientoService {

    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;

    public EstablecimientoResponse crearEstablecimiento(EstablecimientoRequest request, String email) {
        Usuario dueno = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre(request.nombre())
                .direccion(request.direccion())
                .latitud(request.latitud())
                .longitud(request.longitud())
                .requiereSena(request.requiereSena())
                .isActive(true)
                .dueno(dueno)
                .build();

        Establecimiento establecimientoGuardado = establecimientoRepository.save(establecimiento);

        return mapToResponse(establecimientoGuardado);
    }

    public List<EstablecimientoResponse> obtenerMisEstablecimientos(String email) {
        Usuario dueno = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        return establecimientoRepository.findByDuenoIdAndIsActiveTrue(dueno.getId()).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    public EstablecimientoResponse actualizarEstablecimiento(Long id, EstablecimientoRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Establecimiento no encontrado"));

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getEmail().equals(email)) {
            throw new AccessDeniedException("No autorizado para modificar este establecimiento");
        }

        establecimiento.setNombre(request.nombre());
        establecimiento.setDireccion(request.direccion());
        establecimiento.setLatitud(request.latitud());
        establecimiento.setLongitud(request.longitud());
        establecimiento.setRequiereSena(request.requiereSena());

        Establecimiento establecimientoActualizado = establecimientoRepository.save(establecimiento);
        return mapToResponse(establecimientoActualizado);
    }

    private EstablecimientoResponse mapToResponse(Establecimiento establecimiento) {
        return new EstablecimientoResponse(
                establecimiento.getId(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                establecimiento.getLatitud(),
                establecimiento.getLongitud(),
                establecimiento.getRequiereSena(),
                establecimiento.getIsActive(),
                establecimiento.getDueno().getId()
        );
    }
}
