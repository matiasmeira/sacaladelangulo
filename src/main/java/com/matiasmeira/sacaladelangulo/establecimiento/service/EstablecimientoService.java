package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        return new EstablecimientoResponse(
                establecimientoGuardado.getId(),
                establecimientoGuardado.getNombre(),
                establecimientoGuardado.getDireccion(),
                establecimientoGuardado.getLatitud(),
                establecimientoGuardado.getLongitud(),
                establecimientoGuardado.getRequiereSena(),
                establecimientoGuardado.getIsActive(),
                establecimientoGuardado.getDueno().getId()
        );
    }
}
