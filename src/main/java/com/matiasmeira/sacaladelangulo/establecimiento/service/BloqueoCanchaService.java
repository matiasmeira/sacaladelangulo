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
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Transactional
    public BloqueoCanchaResponse crearBloqueo(Long establecimientoId, Long canchaId, BloqueoCanchaRequest request, String email) {
        if (!request.fechaInicio().isBefore(request.fechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }

        Cancha cancha = buscarCanchaDelEstablecimiento(establecimientoId, canchaId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

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

        List<ReservaAfectadaResponse> reservasAfectadasConAlternativas = reservasAfectadas.isEmpty()
                ? List.of()
                : calcularAlternativasParaReservasAfectadas(cancha, reservasAfectadas);

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
     * Para cada reserva afectada por el bloqueo, busca canchas alternativas dentro del
     * mismo establecimiento. Precarga una sola vez las canchas activas y, para el rango
     * completo que abarcan todas las reservas afectadas, los bloqueos y reservas del
     * establecimiento — en vez de repetir esas 3 consultas por cada combinación
     * reserva×cancha candidata (antes O(N×M) queries; ver A14 en la auditoría).
     */
    private List<ReservaAfectadaResponse> calcularAlternativasParaReservasAfectadas(Cancha canchaBloqueada, List<Reserva> reservasAfectadas) {
        Long establecimientoId = canchaBloqueada.getEstablecimiento().getId();
        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId);

        LocalDateTime rangoInicio = reservasAfectadas.stream()
                .map(Reserva::getFechaHoraInicio).min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime rangoFin = reservasAfectadas.stream()
                .map(Reserva::getFechaHoraFin).max(LocalDateTime::compareTo).orElseThrow();

        List<BloqueoCancha> bloqueosEnRango = bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, rangoInicio, rangoFin);
        List<Reserva> reservasEnRango = reservaRepository.findSuperpuestas(establecimientoId, rangoInicio, rangoFin, LocalDateTime.now());

        return reservasAfectadas.stream()
                .map(reserva -> new ReservaAfectadaResponse(
                        reservaMapper.mapToResponse(reserva),
                        buscarCanchasAlternativasDisponibles(canchaBloqueada, reserva, todasLasCanchas, bloqueosEnRango, reservasEnRango)))
                .toList();
    }

    /**
     * Busca, dentro del mismo establecimiento, canchas que soporten el deporte para el
     * que se hizo la reserva afectada (p. ej. "Cancha 5B" para una reserva de FUTBOL_5 en
     * "Cancha 5A" — el deporte granular ya implica el mismo tamaño/modalidad) que estén
     * libres —sin reserva ni bloqueo solapado— en el horario exacto de esa reserva. Se
     * ofrecen como alternativa de reubicación en vez de cancelar directamente
     * (ver PUT /reservas/{id}/mover-cancha).
     */
    private List<CanchaDisponibleResponse> buscarCanchasAlternativasDisponibles(Cancha canchaBloqueada, Reserva reserva,
            List<Cancha> todasLasCanchas, List<BloqueoCancha> bloqueosEnRango, List<Reserva> reservasEnRango) {
        return todasLasCanchas.stream()
                .filter(candidata -> !candidata.getId().equals(canchaBloqueada.getId()))
                .filter(candidata -> candidata.getDeportes().contains(reserva.getDeporteSeleccionado()))
                .filter(candidata -> estaLibreEnElHorarioDeLaReserva(candidata, reserva, bloqueosEnRango, reservasEnRango))
                .map(candidata -> new CanchaDisponibleResponse(candidata.getId(), candidata.getNombre()))
                .toList();
    }

    private boolean estaLibreEnElHorarioDeLaReserva(Cancha candidata, Reserva reserva,
            List<BloqueoCancha> bloqueosEnRango, List<Reserva> reservasEnRango) {
        boolean tieneBloqueo = bloqueosEnRango.stream()
                .filter(b -> b.getCancha().getId().equals(candidata.getId()))
                .anyMatch(b -> seSuperponen(b.getFechaInicio(), b.getFechaFin(), reserva.getFechaHoraInicio(), reserva.getFechaHoraFin()));
        boolean tieneReservaSolapada = reservasEnRango.stream()
                .filter(r -> r.getCancha().getId().equals(candidata.getId()))
                .filter(r -> !r.getId().equals(reserva.getId()))
                .anyMatch(r -> seSuperponen(r.getFechaHoraInicio(), r.getFechaHoraFin(), reserva.getFechaHoraInicio(), reserva.getFechaHoraFin()));
        return !tieneBloqueo && !tieneReservaSolapada;
    }

    private boolean seSuperponen(LocalDateTime inicioA, LocalDateTime finA, LocalDateTime inicioB, LocalDateTime finB) {
        return inicioA.isBefore(finB) && finA.isAfter(inicioB);
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

        autorizacionEmpleadoService.validarPropietarioOAdmin(bloqueo.getCancha().getEstablecimiento(), email);

        bloqueoCanchaRepository.delete(bloqueo);
        log.info("Bloqueo {} eliminado de la cancha {}", bloqueoId, canchaId);
    }

    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorCancha(Long establecimientoId, Long canchaId, String email) {
        Cancha cancha = buscarCanchaDelEstablecimiento(establecimientoId, canchaId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

        return bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaId).stream()
                .map(this::mapSinReservasAfectadas)
                .toList();
    }

    /**
     * Bloqueos de todas las canchas de un establecimiento que se superponen con el día dado.
     * Accesible a cualquier usuario autenticado: sirve para que la grilla de disponibilidad
     * del jugador refleje los horarios bloqueados por el dueño. El motivo (texto libre,
     * puede contener notas operativas internas) no se incluye si quien consulta es PLAYER
     * (ver M30 en la auditoría).
     */
    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorEstablecimientoYFecha(Long establecimientoId, LocalDate fecha, String email) {
        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(LocalTime.MAX);

        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        boolean ocultarMotivo = usuarioAutenticado.getRol() == Role.PLAYER;

        return bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, inicioDia, finDia).stream()
                .map(bloqueo -> mapSinReservasAfectadas(bloqueo, ocultarMotivo))
                .toList();
    }

    private BloqueoCanchaResponse mapSinReservasAfectadas(BloqueoCancha bloqueo) {
        return mapSinReservasAfectadas(bloqueo, false);
    }

    private BloqueoCanchaResponse mapSinReservasAfectadas(BloqueoCancha bloqueo, boolean ocultarMotivo) {
        return new BloqueoCanchaResponse(
                bloqueo.getId(),
                bloqueo.getCancha().getId(),
                bloqueo.getFechaInicio(),
                bloqueo.getFechaFin(),
                ocultarMotivo ? null : bloqueo.getMotivo(),
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

}
