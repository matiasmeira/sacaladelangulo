package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BloqueoCanchaService {

    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final CanchaRepository canchaRepository;
    private final ReservaRepository reservaRepository;
    private final ReservaMapper reservaMapper;
    private final UsuarioRepository usuarioRepository;

    @Transactional
    public BloqueoCanchaResponse crearBloqueo(Long establecimientoId, Long canchaId, BloqueoCanchaRequest request, String email) {
        if (request.fechaInicio().isAfter(request.fechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio no puede ser posterior a la de fin");
        }

        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !cancha.getEstablecimiento().getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(request.fechaInicio())
                .fechaFin(request.fechaFin())
                .motivo(request.motivo())
                .build();

        bloqueoCanchaRepository.save(bloqueo);

        List<Reserva> reservasAfectadas = reservaRepository.findOverlappingByCanchaId(
                canchaId,
                request.fechaInicio(),
                request.fechaFin()
        );

        log.info("Bloqueo creado para cancha {}. Reservas afectadas: {}", canchaId, reservasAfectadas.size());

        return new BloqueoCanchaResponse(
                bloqueo.getId(),
                cancha.getId(),
                bloqueo.getFechaInicio(),
                bloqueo.getFechaFin(),
                bloqueo.getMotivo(),
                reservasAfectadas.stream().map(reservaMapper::mapToResponse).toList()
        );
    }

    @Transactional
    public void eliminarBloqueo(Long establecimientoId, Long canchaId, Long bloqueoId, String email) {
        BloqueoCancha bloqueo = bloqueoCanchaRepository.findById(bloqueoId)
                .orElseThrow(() -> new EntityNotFoundException("Bloqueo no encontrado"));

        if (!bloqueo.getCancha().getId().equals(canchaId)) {
            throw new IllegalArgumentException("El bloqueo no pertenece a esta cancha");
        }
        if (!bloqueo.getCancha().getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !bloqueo.getCancha().getEstablecimiento().getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        bloqueoCanchaRepository.delete(bloqueo);
        log.info("Bloqueo {} eliminado de la cancha {}", bloqueoId, canchaId);
    }

    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorCancha(Long establecimientoId, Long canchaId, String email) {
        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));

        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !cancha.getEstablecimiento().getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }

        return bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaId).stream()
                .map(this::mapSinReservasAfectadas)
                .toList();
    }

    /**
     * Bloqueos de todas las canchas de un establecimiento que se superponen con el día dado.
     * Accesible a cualquier usuario autenticado (no expone datos de jugadores): sirve para que
     * la grilla de disponibilidad del jugador refleje los horarios bloqueados por el dueño.
     */
    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorEstablecimientoYFecha(Long establecimientoId, LocalDate fecha) {
        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(LocalTime.MAX);

        return bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, inicioDia, finDia).stream()
                .map(this::mapSinReservasAfectadas)
                .toList();
    }

    private BloqueoCanchaResponse mapSinReservasAfectadas(BloqueoCancha bloqueo) {
        return new BloqueoCanchaResponse(
                bloqueo.getId(),
                bloqueo.getCancha().getId(),
                bloqueo.getFechaInicio(),
                bloqueo.getFechaFin(),
                bloqueo.getMotivo(),
                List.of()
        );
    }
}
