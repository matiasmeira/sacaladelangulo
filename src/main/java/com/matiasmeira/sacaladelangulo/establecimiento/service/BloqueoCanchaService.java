package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
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
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
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
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BloqueoCanchaService {

    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final CanchaRepository canchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final ReservaMapper reservaMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @Transactional
    public BloqueoCanchaResponse crearBloqueo(Long establecimientoId, Long canchaId, BloqueoCanchaRequest request, String email) {
        Establecimiento establecimiento = autorizarSobreEstablecimiento(establecimientoId, email);
        Cancha cancha = buscarCanchaDelEstablecimiento(establecimientoId, canchaId);

        if (!request.fechaInicio().isBefore(request.fechaFin())) {
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }

        establecimientoOperativoGuard.validarPuedeGenerarCompromisosNuevos(establecimiento);

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
     * reserva×cancha candidata (antes O(N×M) queries).
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
        autorizarSobreEstablecimiento(establecimientoId, email);

        // Acotado a la cancha y al establecimiento del path: inexistente o de otro lado, el mismo 404.
        BloqueoCancha bloqueo = bloqueoCanchaRepository.findById(bloqueoId)
                .filter(b -> b.getCancha().getId().equals(canchaId))
                .filter(b -> b.getCancha().getEstablecimiento().getId().equals(establecimientoId))
                .orElseThrow(() -> new EntityNotFoundException("Bloqueo no encontrado"));

        bloqueoCanchaRepository.delete(bloqueo);
        log.info("Bloqueo {} eliminado de la cancha {}", bloqueoId, canchaId);
    }

    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorCancha(Long establecimientoId, Long canchaId, String email) {
        autorizarSobreEstablecimiento(establecimientoId, email);
        buscarCanchaDelEstablecimiento(establecimientoId, canchaId);

        return bloqueoCanchaRepository.findByCanchaIdOrderByFechaInicioAsc(canchaId).stream()
                .map(this::mapSinReservasAfectadas)
                .toList();
    }

    /**
     * Bloqueos de todas las canchas de un establecimiento que se superponen con el día dado.
     * Accesible a cualquier usuario autenticado: sirve para que la grilla de disponibilidad
     * del jugador refleje los horarios bloqueados por el dueño. El motivo (texto libre,
     * puede contener notas operativas internas) sólo se incluye si quien consulta tiene
     * acceso de panel al establecimiento (ADMIN, su dueño o un empleado suyo con cualquier
     * permiso, el mismo criterio que la disponibilidad); para el resto es null. El establecimiento se toma de los bloqueos devueltos; si no hay
     * ninguno se responde [] sin consultar la regla.
     */
    @Transactional(readOnly = true)
    public List<BloqueoCanchaResponse> listarPorEstablecimientoYFecha(Long establecimientoId, LocalDate fecha, String email) {
        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(LocalTime.MAX);

        List<BloqueoCancha> bloqueos = bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, inicioDia, finDia);
        if (bloqueos.isEmpty()) {
            return List.of();
        }

        // Todos los bloqueos son del mismo establecimiento (lo filtra la query): la regla se calcula una vez.
        Establecimiento establecimiento = bloqueos.get(0).getCancha().getEstablecimiento();
        boolean ocultarMotivo = !autorizacionEmpleadoService.tieneAccesoDePanel(
                establecimiento, email, EnumSet.allOf(PermisoEmpleado.class));

        return bloqueos.stream()
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

    /**
     * Autoriza contra el establecimiento del path ANTES de buscar cualquier sub-recurso: un establecimiento
     * inexistente responde igual que uno ajeno (ver EstablecimientoAutorizado).
     */
    private Establecimiento autorizarSobreEstablecimiento(Long establecimientoId, String email) {
        return EstablecimientoAutorizado.autorizar(establecimientoRepository.findById(establecimientoId),
                establecimiento -> autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email));
    }

    // A diferencia de ReservaService.buscarCanchaPorId, acá no hace falta validar isActive:
    // sólo dueño/admin llegan a este método (crearBloqueo/listarPorCancha), y un bloqueo
    // sobre una cancha ya inactiva es redundante, no peligroso -no le da una reserva a nadie-.
    // deletedAt sí corta: una cancha ELIMINADA (ver CanchaEliminacionService) desapareció de
    // todas las vistas del dueño, y no hay ningún caso de uso legítimo para crearle un
    // bloqueo nuevo o listar los suyos.
    private Cancha buscarCanchaDelEstablecimiento(Long establecimientoId, Long canchaId) {
        // Inexistente, eliminada o de otro establecimiento: el mismo 404, sin distinguir.
        return canchaRepository.findById(canchaId)
                .filter(cancha -> cancha.getDeletedAt() == null)
                .filter(cancha -> cancha.getEstablecimiento().getId().equals(establecimientoId))
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));
    }

}
