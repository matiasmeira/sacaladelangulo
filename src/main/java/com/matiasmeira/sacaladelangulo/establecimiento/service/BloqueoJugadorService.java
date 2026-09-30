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
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestiona el bloqueo de jugadores por establecimiento (ver BloqueoJugador). Solo el
 * dueño real del establecimiento o un administrador pueden bloquear/desbloquear/listar.
 *
 * <p>Sólo se puede bloquear a un jugador con al menos una reserva (en cualquier estado, igual que la ficha
 * de Clientes) en el establecimiento. Se autoriza antes de consultar al jugador, y cualquier otro id
 * responde el mismo 404 sin distinguir si existe, para no filtrar nombre/email ni permitir enumerar ids.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BloqueoJugadorService {

    static final String MENSAJE_JUGADOR_NO_ENCONTRADO = "Jugador no encontrado en este establecimiento";

    private final BloqueoJugadorRepository bloqueoJugadorRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReservaRepository reservaRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @Transactional
    public BloqueoJugadorResponse crearBloqueo(Long establecimientoId, BloqueoJugadorRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimiento(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);
        establecimientoOperativoGuard.validarPuedeGenerarCompromisosNuevos(establecimiento);

        // Criterio unico: jugador (PLAYER) con al menos una reserva en este establecimiento. Cualquier otro
        // id (inexistente, no jugador, sin reservas aca) responde el mismo 404 para no filtrar datos ni
        // permitir enumerar ids.
        Usuario jugador = usuarioRepository.findById(request.jugadorId())
                .filter(u -> u.getRol() == Role.PLAYER)
                .filter(u -> reservaRepository.existsByJugador_IdAndCancha_Establecimiento_Id(u.getId(), establecimientoId))
                .orElseThrow(() -> new EntityNotFoundException(MENSAJE_JUGADOR_NO_ENCONTRADO));

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
