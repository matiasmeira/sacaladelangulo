package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
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

        return establecimientoRepository.findByDuenoIdAndIsActiveTrue(dueno.getId()).stream()
                .map(this::mapToResponse)
                .toList();
    }

    /**
     * Busca establecimientos cercanos y, si se indica fecha/hora, filtra a los que tengan
     * al menos una cancha libre en esa ventana. Resuelto en un puñado de consultas en lote
     * (en vez de una consulta por establecimiento y otra por cancha) para que este endpoint
     * público no degrade con el volumen de datos.
     */
    @Transactional(readOnly = true)
    public List<EstablecimientoResponse> buscarEstablecimientos(Double latitud, Double longitud, Double distanciaKm, String deporte, LocalDate fecha, LocalTime hora) {
        Double radioBusqueda = (distanciaKm != null && distanciaKm > 0) ? distanciaKm : 10.0;
        List<Establecimiento> establecimientosCercanos = establecimientoRepository.findCercanosYPorDeporte(latitud, longitud, radioBusqueda, deporte);

        if (fecha == null || hora == null) {
            return establecimientosCercanos.stream()
                    .map(this::mapToResponse)
                    .toList();
        }

        List<Long> establecimientoIds = establecimientosCercanos.stream().map(Establecimiento::getId).toList();
        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(establecimientoIds);
        if (deporte != null) {
            canchas = canchas.stream()
                    .filter(c -> c.getDeporte().equalsIgnoreCase(deporte))
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

        return establecimientosCercanos.stream()
                .filter(est -> canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()).stream()
                        .anyMatch(c -> !canchasOcupadas.contains(c.getId())))
                .map(this::mapToResponse)
                .toList();
    }

    public EstablecimientoResponse actualizarEstablecimiento(Long id, EstablecimientoRequest request, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(id);
        Usuario usuarioAutenticado = validarPropietario(establecimiento, email);

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
        return horarios.stream()
                .map(dto -> HorarioAtencion.builder()
                        .diaSemana(dto.diaSemana())
                        .horaApertura(dto.horaApertura())
                        .horaCierre(dto.horaCierre())
                        .establecimiento(establecimiento)
                        .build())
                .collect(Collectors.toList());
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
     * Valida que el usuario autenticado sea el dueño del establecimiento o un administrador.
     */
    private Usuario validarPropietario(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);
        if (usuarioAutenticado.getRol() != Role.ADMIN && !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado para modificar este establecimiento");
        }
        return usuarioAutenticado;
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
                establecimiento.getDueno().getId(),
                establecimiento.getHorariosAtencion() == null ? List.of() : establecimiento.getHorariosAtencion().stream()
                        .map(h -> new HorarioAtencionDto(h.getDiaSemana(), h.getHoraApertura(), h.getHoraCierre()))
                        .toList()
        );
    }
}
