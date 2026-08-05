package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestiona el bloqueo de jugadores por establecimiento (ver BloqueoJugador). Solo el
 * dueño real del establecimiento o un administrador pueden bloquear/desbloquear/listar.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BloqueoJugadorService {

    private final BloqueoJugadorRepository bloqueoJugadorRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Transactional
    public BloqueoJugadorResponse crearBloqueo(Long establecimientoId, BloqueoJugadorRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Usuario jugador = usuarioRepository.findById(request.jugadorId())
                .orElseThrow(() -> new EntityNotFoundException("Jugador no encontrado"));
        if (jugador.getRol() != Role.PLAYER) {
            throw new IllegalArgumentException("El usuario indicado no corresponde a un jugador (rol PLAYER)");
        }

        if (bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimientoId, jugador.getId())) {
            throw new IllegalArgumentException("Este jugador ya está bloqueado en este establecimiento");
        }

        BloqueoJugador bloqueo = BloqueoJugador.builder()
                .establecimiento(establecimiento)
                .jugador(jugador)
                .motivo(request.motivo())
                .build();

        BloqueoJugador guardado = bloqueoJugadorRepository.save(bloqueo);
        log.info("Jugador {} bloqueado en el establecimiento {}", jugador.getId(), establecimientoId);

        return mapToResponse(guardado);
    }

    @Transactional
    public void eliminarBloqueo(Long establecimientoId, Long jugadorId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        BloqueoJugador bloqueo = bloqueoJugadorRepository.findByEstablecimientoIdAndJugadorId(establecimientoId, jugadorId)
                .orElseThrow(() -> new EntityNotFoundException("Este jugador no está bloqueado en este establecimiento"));

        bloqueoJugadorRepository.delete(bloqueo);
        log.info("Jugador {} desbloqueado del establecimiento {}", jugadorId, establecimientoId);
    }

    @Transactional(readOnly = true)
    public List<BloqueoJugadorResponse> listarBloqueados(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return bloqueoJugadorRepository.findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimientoId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    private BloqueoJugadorResponse mapToResponse(BloqueoJugador bloqueo) {
        Usuario jugador = bloqueo.getJugador();
        return new BloqueoJugadorResponse(
                bloqueo.getId(),
                jugador.getId(),
                jugador.getNombre(),
                jugador.getEmail(),
                bloqueo.getMotivo(),
                bloqueo.getFechaBloqueo()
        );
    }

    private Establecimiento buscarEstablecimiento(Long establecimientoId) {
        return establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
