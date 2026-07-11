package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaDisponibleResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ReservaAfectadaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
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
        if (!request.fechaInicio().isBefore(request.fechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }

        Cancha cancha = buscarCanchaDelEstablecimiento(establecimientoId, canchaId);
        validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

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

        List<ReservaAfectadaResponse> reservasAfectadasConAlternativas = reservasAfectadas.stream()
                .map(reserva -> new ReservaAfectadaResponse(
                        reservaMapper.mapToResponse(reserva),
                        buscarCanchasAlternativasDisponibles(cancha, reserva)))
                .toList();

        return new BloqueoCanchaResponse(
                bloqueo.getId(),
                cancha.getId(),
                bloqueo.getFechaInicio(),
                bloqueo.getFechaFin(),
                bloqueo.getMotivo(),
                reservasAfectadasConAlternativas
        );
    }

    /**
     * Busca, dentro del mismo establecimiento, canchas que soporten el deporte para el
     * que se hizo la reserva afectada y tengan la misma capacidad que la cancha bloqueada
     * (p. ej. "Cancha 5B" para una reserva de fútbol en "Cancha 5A") que estén libres
     * —sin reserva ni bloqueo solapado— en el horario exacto de esa reserva. Se ofrecen
     * como alternativa de reubicación en vez de cancelar directamente
     * (ver PUT /reservas/{id}/mover-cancha).
     */
    private List<CanchaDisponibleResponse> buscarCanchasAlternativasDisponibles(Cancha canchaBloqueada, Reserva reserva) {
        return canchaRepository.findByEstablecimientoIdAndIsActiveTrue(canchaBloqueada.getEstablecimiento().getId()).stream()
                .filter(candidata -> !candidata.getId().equals(canchaBloqueada.getId()))
                .filter(candidata -> candidata.getDeportes().contains(reserva.getDeporteSeleccionado()))
                .filter(candidata -> candidata.getCapacidad().equals(canchaBloqueada.getCapacidad()))
                .filter(candidata -> estaLibreEnElHorarioDeLaReserva(candidata, reserva))
                .map(candidata -> new CanchaDisponibleResponse(candidata.getId(), candidata.getNombre()))
                .toList();
    }

    private boolean estaLibreEnElHorarioDeLaReserva(Cancha candidata, Reserva reserva) {
        boolean tieneBloqueo = !bloqueoCanchaRepository.findOverlappingBloqueos(
                candidata.getId(), reserva.getFechaHoraInicio(), reserva.getFechaHoraFin()).isEmpty();
        boolean tieneReservaSolapada = reservaRepository.findOverlappingByCanchaId(
                        candidata.getId(), reserva.getFechaHoraInicio(), reserva.getFechaHoraFin()).stream()
                .anyMatch(r -> !r.getId().equals(reserva.getId()));
        return !tieneBloqueo && !tieneReservaSolapada;
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

        validarPropietarioOAdmin(bloqueo.getCancha().getEstablecimiento(), email);

        bloqueoCanchaRepository.delete(bloqueo);
        log.info("Bloqueo {} eliminado de la cancha {}", bloqueoId, canchaId);
    }

    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorCancha(Long establecimientoId, Long canchaId, String email) {
        Cancha cancha = buscarCanchaDelEstablecimiento(establecimientoId, canchaId);
        validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

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

    private Cancha buscarCanchaDelEstablecimiento(Long establecimientoId, Long canchaId) {
        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));
        if (!cancha.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La cancha no pertenece a este establecimiento");
        }
        return cancha;
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
