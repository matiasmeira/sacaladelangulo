package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.FeedbackDestacadoDto;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para establecimientos.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EstablecimientoService {

    /** Ventana de disponibilidad usada por la búsqueda rápida de establecimientos. */
    private static final int VENTANA_DISPONIBILIDAD_MINUTOS = 60;

    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final FeedbackRepository feedbackRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;

    public EstablecimientoResponse crearEstablecimiento(EstablecimientoRequest request, String email) {
        Usuario dueno = buscarUsuarioPorEmail(email);

        boolean requiereSenaForzada = esPlanLimitado(dueno.getPlanSuscripcion());

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre(request.nombre())
                .direccion(request.direccion())
                .latitud(request.latitud())
                .longitud(request.longitud())
                .requiereSena(requiereSenaForzada || request.requiereSena())
                .isActive(true)
                .dueno(dueno)
                .build();

        establecimiento.setHorariosAtencion(mapearHorarios(request.horariosAtencion(), establecimiento));

        Establecimiento establecimientoGuardado = establecimientoRepository.save(establecimiento);

        return mapToResponse(establecimientoGuardado);
    }

    @Transactional(readOnly = true)
    public List<EstablecimientoResponse> obtenerMisEstablecimientos(String email) {
        Usuario dueno = buscarUsuarioPorEmail(email);

        List<Establecimiento> establecimientos = establecimientoRepository.findByDuenoIdAndIsActiveTrue(dueno.getId());
        return mapearConCalificaciones(establecimientos);
    }

    /**
     * Busca establecimientos cercanos y, si se indica fecha/hora, filtra a los que tengan
     * al menos una cancha libre en esa ventana. Resuelto en un puñado de consultas en lote
     * (en vez de una consulta por establecimiento y otra por cancha) para que este endpoint
     * público no degrade con el volumen de datos.
     */
    @Transactional(readOnly = true)
    public List<EstablecimientoResponse> buscarEstablecimientos(Double latitud, Double longitud, Double distanciaKm, Deporte deporte, LocalDate fecha, LocalTime hora) {
        Double radioBusqueda = (distanciaKm != null && distanciaKm > 0) ? distanciaKm : 10.0;
        List<Establecimiento> establecimientosCercanos = establecimientoRepository.findCercanosYPorDeporte(latitud, longitud, radioBusqueda, deporte);

        if (fecha == null || hora == null) {
            return mapearConCalificaciones(establecimientosCercanos);
        }

        List<Long> establecimientoIds = establecimientosCercanos.stream().map(Establecimiento::getId).toList();
        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(establecimientoIds);
        if (deporte != null) {
            canchas = canchas.stream()
                    .filter(c -> c.getDeportes().contains(deporte))
                    .toList();
        }

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchas.stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        LocalDateTime inicioReserva = LocalDateTime.of(fecha, hora);
        LocalDateTime finReserva = inicioReserva.plusMinutes(VENTANA_DISPONIBILIDAD_MINUTOS);

        List<Long> canchaIds = canchas.stream().map(Cancha::getId).toList();
        Set<Long> canchasOcupadas = canchaIds.isEmpty()
                ? Set.of()
                : new HashSet<>(reservaRepository.findCanchaIdsConSolapamiento(canchaIds, inicioReserva, finReserva));

        List<Establecimiento> establecimientosDisponibles = establecimientosCercanos.stream()
                .filter(est -> canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()).stream()
                        .anyMatch(c -> !canchasOcupadas.contains(c.getId())))
                .toList();
        return mapearConCalificaciones(establecimientosDisponibles);
    }

    public EstablecimientoResponse actualizarEstablecimiento(Long id, EstablecimientoRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(id);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        establecimiento.setNombre(request.nombre());
        establecimiento.setDireccion(request.direccion());
        establecimiento.setLatitud(request.latitud());
        establecimiento.setLongitud(request.longitud());
        establecimiento.setRequiereSena(esPlanLimitado(usuarioAutenticado.getPlanSuscripcion()) || request.requiereSena());

        if (establecimiento.getHorariosAtencion() == null) {
            establecimiento.setHorariosAtencion(new ArrayList<>());
        }
        establecimiento.getHorariosAtencion().clear();
        establecimiento.getHorariosAtencion().addAll(mapearHorarios(request.horariosAtencion(), establecimiento));

        Establecimiento establecimientoActualizado = establecimientoRepository.save(establecimiento);
        return mapToResponse(establecimientoActualizado);
    }

    private boolean esPlanLimitado(PlanSuscripcion plan) {
        return plan == PlanSuscripcion.TRIAL || plan == PlanSuscripcion.FREE;
    }

    private List<HorarioAtencion> mapearHorarios(List<HorarioAtencionDto> horarios, Establecimiento establecimiento) {
        if (horarios == null) {
            return new ArrayList<>();
        }
        validarHorarios(horarios);
        return horarios.stream()
                .map(dto -> HorarioAtencion.builder()
                        .diaSemana(dto.diaSemana())
                        .horaApertura(dto.horaApertura())
                        .horaCierre(dto.horaCierre())
                        .establecimiento(establecimiento)
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * Un mismo día de la semana no puede tener más de un horario cargado (si no, la
     * validación de disponibilidad en ReservaService quedaría ambigua sobre cuál usar),
     * y un horario con apertura == cierre no bloquea nada realmente (equivale a un día
     * cerrado, pero de forma confusa) por lo que se rechaza explícitamente.
     */
    private void validarHorarios(List<HorarioAtencionDto> horarios) {
        Set<DayOfWeek> diasVistos = new HashSet<>();
        for (HorarioAtencionDto horario : horarios) {
            if (!diasVistos.add(horario.diaSemana())) {
                throw new IllegalArgumentException("No puede haber más de un horario de atención para el " + horario.diaSemana());
            }
            if (horario.horaApertura().equals(horario.horaCierre())) {
                throw new IllegalArgumentException("La hora de apertura y cierre no pueden ser iguales (" + horario.diaSemana() + ")");
            }
        }
    }

    private Usuario buscarUsuarioPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }

    /**
     * Mapea un único establecimiento (alta/actualización), resolviendo su promedio de
     * calificación y comentario destacado con consultas puntuales: no hay riesgo de N+1
     * porque es un solo establecimiento.
     */
    private EstablecimientoResponse mapToResponse(Establecimiento establecimiento) {
        Double promedio = feedbackRepository.calcularPromedioByEstablecimientoId(establecimiento.getId());
        Long cantidad = feedbackRepository.contarByEstablecimientoId(establecimiento.getId());
        FeedbackDestacadoDto destacado = feedbackRepository.findDestacadoByEstablecimientoId(establecimiento.getId())
                .map(this::mapFeedbackDestacado)
                .orElse(null);
        return construirResponse(establecimiento, promedio, cantidad != null ? cantidad : 0L, destacado);
    }

    /**
     * Mapea una lista de establecimientos resolviendo promedio/cantidad/destacado en un
     * puñado de consultas agrupadas por establecimiento.id IN (...), en vez de una consulta
     * por establecimiento, para no degradar los endpoints de listado (uno de ellos público).
     */
    private List<EstablecimientoResponse> mapearConCalificaciones(List<Establecimiento> establecimientos) {
        List<Long> ids = establecimientos.stream().map(Establecimiento::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, Double> promedios = new HashMap<>();
        for (Object[] fila : feedbackRepository.calcularPromediosPorEstablecimientos(ids)) {
            promedios.put((Long) fila[0], (Double) fila[1]);
        }

        Map<Long, Long> cantidades = new HashMap<>();
        for (Object[] fila : feedbackRepository.contarPorEstablecimientos(ids)) {
            cantidades.put((Long) fila[0], (Long) fila[1]);
        }

        Map<Long, Feedback> destacados = feedbackRepository.findDestacadosByEstablecimientoIdIn(ids).stream()
                .collect(Collectors.toMap(f -> f.getReserva().getCancha().getEstablecimiento().getId(), f -> f));

        return establecimientos.stream()
                .map(est -> construirResponse(
                        est,
                        promedios.get(est.getId()),
                        cantidades.getOrDefault(est.getId(), 0L),
                        destacados.containsKey(est.getId()) ? mapFeedbackDestacado(destacados.get(est.getId())) : null))
                .toList();
    }

    private EstablecimientoResponse construirResponse(Establecimiento establecimiento, Double promedioCalificacion,
                                                        Long cantidadCalificaciones, FeedbackDestacadoDto comentarioDestacado) {
        return new EstablecimientoResponse(
                establecimiento.getId(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                establecimiento.getLatitud(),
                establecimiento.getLongitud(),
                establecimiento.getRequiereSena(),
                establecimiento.getIsActive(),
                establecimiento.getDueno().getId(),
                establecimiento.getHorariosAtencion() == null ? List.of() : establecimiento.getHorariosAtencion().stream()
                        .map(h -> new HorarioAtencionDto(h.getDiaSemana(), h.getHoraApertura(), h.getHoraCierre()))
                        .toList(),
                promedioCalificacion,
                cantidadCalificaciones,
                comentarioDestacado
        );
    }

    private FeedbackDestacadoDto mapFeedbackDestacado(Feedback feedback) {
        Usuario jugador = feedback.getReserva().getJugador();
        return new FeedbackDestacadoDto(
                feedback.getId(),
                feedback.getPuntuacion(),
                feedback.getComentario(),
                jugador != null ? jugador.getNombre() : null,
                feedback.getFechaCreacion()
        );
    }
}
