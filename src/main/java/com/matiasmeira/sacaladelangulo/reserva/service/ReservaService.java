package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para reservas.
 * Implementa lógica de validación de solapamientos y disponibilidad de canchas lógicas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReservaService {

    private static final int TAMANIO_PAGINA_MAXIMO = 100;

    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReservaMapper reservaMapper;

    /**
     * Crea una nueva reserva con validación de solapamientos y disponibilidad de pool.
     *
     * @param request DTO con datos de la reserva
     * @param email Email del usuario autenticado (jugador)
     * @return ReservaResponse con los datos de la reserva creada
     */
    public ReservaResponse crearReserva(ReservaRequest request, String email) {
        log.info("Iniciando creación de reserva. Email: {}, Cancha: {}, Inicio: {}, Fin: {}",
                email, request.canchaId(), request.fechaHoraInicio(), request.fechaHoraFin());

        Usuario jugador = buscarUsuarioPorEmail(email);
        Cancha cancha = buscarCanchaPorId(request.canchaId());

        validarFechas(request);
        validarGranularidadHoraria(request, cancha);
        long duracionMinutos = validarDuracion(request, cancha);
        validarSinBloqueos(request, cancha);
        validarHorarioAtencion(request, cancha.getEstablecimiento());

        List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                cancha.getEstablecimiento().getId(),
                request.fechaHoraInicio(),
                request.fechaHoraFin()
        );
        log.debug("Se encontraron {} reservas solapadas en el predio", solapadas.size());

        validarCanchaExactaLibre(cancha, solapadas);
        validarPoolCanchas(cancha, solapadas);

        BigDecimal precioCalculado = calcularPrecio(cancha, request, duracionMinutos);

        Reserva reserva = Reserva.builder()
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(request.fechaHoraInicio())
                .fechaHoraFin(request.fechaHoraFin())
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(precioCalculado)
                .senaPagada(BigDecimal.ZERO)
                .build();

        Reserva reservaGuardada = reservaRepository.save(reserva);
        log.info("Nueva reserva creada con éxito. ID: {}, Cancha: {}, Jugador: {}",
                reservaGuardada.getId(), cancha.getNombre(), jugador.getNombre());

        return reservaMapper.mapToResponse(reservaGuardada);
    }

    private Usuario buscarUsuarioPorEmail(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        log.debug("Usuario encontrado: {}", usuario.getId());
        return usuario;
    }

    private Cancha buscarCanchaPorId(Long canchaId) {
        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));
        log.debug("Cancha encontrada: {} - Establecimiento: {}", cancha.getId(), cancha.getEstablecimiento().getId());
        return cancha;
    }

    private void validarFechas(ReservaRequest request) {
        if (!request.fechaHoraInicio().isBefore(request.fechaHoraFin())) {
            log.warn("Fechas inválidas. Inicio >= Fin");
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }
        if (request.fechaHoraInicio().isBefore(LocalDateTime.now())) {
            log.warn("Reserva solicitada en el pasado");
            throw new IllegalArgumentException("No se pueden crear reservas en el pasado");
        }
    }

    private void validarGranularidadHoraria(ReservaRequest request, Cancha cancha) {
        int minutoInicio = request.fechaHoraInicio().getMinute();
        if (minutoInicio != 0 && minutoInicio != 30) {
            log.warn("Inicio de reserva inválido: {}", request.fechaHoraInicio());
            throw new IllegalArgumentException("Las reservas solo pueden iniciar en punto (:00) o y media (:30)");
        }
        if (Boolean.FALSE.equals(cancha.getPermiteInicioMediaHora()) && minutoInicio != 0) {
            log.warn("Cancha no permite inicio a media hora: {}", request.fechaHoraInicio());
            throw new IllegalArgumentException("Esta cancha solo permite iniciar reservas en horas en punto exactas (:00)");
        }
    }

    private long validarDuracion(ReservaRequest request, Cancha cancha) {
        long duracionMinutos = Duration.between(request.fechaHoraInicio(), request.fechaHoraFin()).toMinutes();
        if (!cancha.getDuracionesPermitidas().contains((int) duracionMinutos)) {
            log.warn("Duración no permitida: {} minutos", duracionMinutos);
            throw new IllegalArgumentException("Duración no permitida. Opciones válidas: " + cancha.getDuracionesPermitidas() + " minutos");
        }
        return duracionMinutos;
    }

    private void validarSinBloqueos(ReservaRequest request, Cancha cancha) {
        List<BloqueoCancha> bloqueos = bloqueoCanchaRepository.findOverlappingBloqueos(
                cancha.getId(), request.fechaHoraInicio(), request.fechaHoraFin());
        if (!bloqueos.isEmpty()) {
            throw new IllegalArgumentException("La cancha se encuentra bloqueada en ese horario. Motivo: " + bloqueos.get(0).getMotivo());
        }
    }

    private void validarHorarioAtencion(ReservaRequest request, Establecimiento establecimiento) {
        DayOfWeek diaSemana = request.fechaHoraInicio().getDayOfWeek();
        LocalTime horaInicio = request.fechaHoraInicio().toLocalTime();
        LocalTime horaFin = request.fechaHoraFin().toLocalTime();

        Optional<HorarioAtencion> horarioOpt = establecimiento.getHorariosAtencion() == null ? Optional.empty() :
                establecimiento.getHorariosAtencion().stream()
                        .filter(h -> h.getDiaSemana() == diaSemana)
                        .findFirst();

        if (horarioOpt.isEmpty()) {
            log.warn("El establecimiento está cerrado el día {}", diaSemana);
            throw new IllegalArgumentException("El establecimiento está cerrado el " + diaSemana);
        }

        HorarioAtencion horario = horarioOpt.get();
        boolean cruzaMedianoche = horario.getHoraCierre().isBefore(horario.getHoraApertura());
        boolean horaInicioValida;
        boolean horaFinValida;

        if (cruzaMedianoche) {
            horaInicioValida = !horaInicio.isBefore(horario.getHoraApertura()) || !horaInicio.isAfter(horario.getHoraCierre());
            horaFinValida = !horaFin.isBefore(horario.getHoraApertura()) || !horaFin.isAfter(horario.getHoraCierre());
        } else {
            horaInicioValida = !horaInicio.isBefore(horario.getHoraApertura()) && !horaInicio.isAfter(horario.getHoraCierre());
            horaFinValida = !horaFin.isBefore(horario.getHoraApertura()) && !horaFin.isAfter(horario.getHoraCierre());
        }

        if (!horaInicioValida || !horaFinValida) {
            log.warn("Reserva fuera del horario de atención nocturno: inicio={} fin={} apertura={} cierre={}",
                    horaInicio, horaFin, horario.getHoraApertura(), horario.getHoraCierre());
            throw new IllegalArgumentException("El horario solicitado se encuentra fuera del horario de atención del establecimiento para este día.");
        }
    }

    private void validarCanchaExactaLibre(Cancha cancha, List<Reserva> solapadas) {
        boolean canchaExactaReservada = solapadas.stream()
                .anyMatch(r -> r.getCancha().getId().equals(cancha.getId()));

        if (canchaExactaReservada) {
            log.warn("Cancha exacta ya está reservada. Cancha: {}", cancha.getId());
            throw new IllegalArgumentException("La cancha exacta ya está reservada en ese horario");
        }
        log.debug("Validación de cancha exacta: OK");
    }

    private BigDecimal calcularPrecio(Cancha cancha, ReservaRequest request, long duracionMinutos) {
        BigDecimal duracionHoras = BigDecimal.valueOf(duracionMinutos)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal precioPorHora = cancha.getPrecioBase();
        for (var tarifa : cancha.getTarifas()) {
            if (tarifa.getDiaSemana() == request.fechaHoraInicio().getDayOfWeek() &&
                    !request.fechaHoraInicio().toLocalTime().isBefore(tarifa.getHoraInicio()) &&
                    request.fechaHoraInicio().toLocalTime().isBefore(tarifa.getHoraFin())) {
                precioPorHora = tarifa.getPrecio();
                break;
            }
        }
        return precioPorHora.multiply(duracionHoras);
    }

    /**
     * Confirma una reserva y cambia su estado a CONFIRMADA.
     * Solo el dueño del establecimiento o un administrador puede confirmar.
     *
     * @param reservaId ID de la reserva a confirmar
     * @param email Email del usuario autenticado
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse confirmarReserva(Long reservaId, String email) {
        log.info("Iniciando confirmación de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));
        log.debug("Reserva encontrada. Estado actual: {}", reserva.getEstado());

        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);

        Long duenioEstablecimientoId = reserva.getCancha().getEstablecimiento().getDueno().getId();
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !usuarioAutenticado.getId().equals(duenioEstablecimientoId)) {
            log.warn("Acceso denegado. Usuario: {}, Dueño: {}", usuarioAutenticado.getId(), duenioEstablecimientoId);
            throw new AccessDeniedException("No está autorizado para confirmar esta reserva");
        }

        reserva.setEstado(EstadoReserva.CONFIRMADA);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Reserva confirmada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());

        return reservaMapper.mapToResponse(reservaActualizada);
    }

    public ReservaResponse cancelarReserva(Long reservaId, String email) {
        log.info("Iniciando cancelación de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));
        log.debug("Reserva encontrada. Estado actual: {}", reserva.getEstado());

        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);

        Long duenioEstablecimientoId = reserva.getCancha().getEstablecimiento().getDueno().getId();
        boolean esAdmin = usuarioAutenticado.getRol() == Role.ADMIN;
        boolean esDuenoDelEstablecimiento = usuarioAutenticado.getId().equals(duenioEstablecimientoId);
        boolean esElJugador = reserva.getJugador().getId().equals(usuarioAutenticado.getId());

        if (!esAdmin && !esDuenoDelEstablecimiento && !esElJugador) {
            log.warn("Acceso denegado a cancelar reserva. Usuario: {}, Dueño: {}", usuarioAutenticado.getId(), duenioEstablecimientoId);
            throw new AccessDeniedException("No está autorizado para cancelar esta reserva");
        }

        if (esElJugador && !esAdmin && !esDuenoDelEstablecimiento) {
            validarPlazoDeCancelacion(reserva);
        }

        if (reserva.getEstado() == EstadoReserva.CANCELADA) {
            log.info("Reserva ya se encuentra cancelada. ID: {}", reservaId);
            return reservaMapper.mapToResponse(reserva);
        }

        reserva.setEstado(EstadoReserva.CANCELADA);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Reserva cancelada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());

        return reservaMapper.mapToResponse(reservaActualizada);
    }

    private void validarPlazoDeCancelacion(Reserva reserva) {
        Establecimiento establecimiento = reserva.getCancha().getEstablecimiento();

        int horasPermitidas = establecimiento.getHorasCancelacionAntesPartido() != null ? establecimiento.getHorasCancelacionAntesPartido() : 24;
        int minutosGracia = establecimiento.getMinutosGraciaCancelacion() != null ? establecimiento.getMinutosGraciaCancelacion() : 30;

        long horasRestantes = Duration.between(LocalDateTime.now(), reserva.getFechaHoraInicio()).toHours();
        long minutosDesdeCreacion = Duration.between(reserva.getFechaCreacion(), LocalDateTime.now()).toMinutes();

        boolean dentroDelPeriodoDeGracia = minutosDesdeCreacion <= minutosGracia;
        boolean antesDelLimiteDeCancelacion = horasRestantes >= horasPermitidas;

        if (!antesDelLimiteDeCancelacion && !dentroDelPeriodoDeGracia) {
            log.warn("Jugador intentó cancelar fuera de término. Restantes: {}h, Desde creación: {}m", horasRestantes, minutosDesdeCreacion);
            throw new IllegalArgumentException(String.format(
                    "Solo puedes cancelar con al menos %d horas de anticipación, o dentro de los %d minutos posteriores a realizar la reserva.",
                    horasPermitidas, minutosGracia));
        }
    }

    /**
     * Valida que haya disponibilidad en el pool de canchas lógicas.
     * Si la cancha solicitada es física y pertenece a un pool, valida el uso total del pool.
     *
     * @param cancha Cancha solicitada
     * @param solapadas Reservas solapadas en el establecimiento
     */
    private void validarPoolCanchas(Cancha cancha, List<Reserva> solapadas) {
        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(
                cancha.getEstablecimiento().getId()
        );

        for (Cancha canchaDelPool : todasLasCanchas) {
            if (canchaDelPool.getCanchasFisicas() != null && !canchaDelPool.getCanchasFisicas().isEmpty()) {
                List<Long> poolIds = canchaDelPool.getCanchasFisicas().stream()
                        .map(Cancha::getId)
                        .collect(Collectors.toList());

                log.debug("Cancha lógica encontrada: {} con {} canchas físicas", canchaDelPool.getId(), poolIds.size());

                boolean afectaEstePool = canchaDelPool.getId().equals(cancha.getId()) ||
                        poolIds.contains(cancha.getId());

                if (afectaEstePool) {
                    log.debug("La cancha solicitada afecta el pool. Validando disponibilidad...");

                    int usoActual = 0;
                    for (Reserva reservaSolapada : solapadas) {
                        Long canchaReservadaId = reservaSolapada.getCancha().getId();

                        if (poolIds.contains(canchaReservadaId)) {
                            usoActual += 1;
                            log.debug("Uso actual +1 (cancha física): total = {}", usoActual);
                        } else if (canchaReservadaId.equals(canchaDelPool.getId())) {
                            Integer canchasNecesarias = reservaSolapada.getCancha().getCanchasNecesarias();
                            if (canchasNecesarias != null && canchasNecesarias > 0) {
                                usoActual += canchasNecesarias;
                                log.debug("Uso actual +{} (cancha lógica): total = {}", canchasNecesarias, usoActual);
                            }
                        }
                    }

                    int usoNuevo = cancha.getId().equals(canchaDelPool.getId()) ?
                            (cancha.getCanchasNecesarias() != null ? cancha.getCanchasNecesarias() : 1) : 1;
                    log.debug("Uso nuevo: {}", usoNuevo);

                    int capacidadPool = poolIds.size();
                    if (usoActual + usoNuevo > capacidadPool) {
                        log.warn("No hay disponibilidad en el pool. Uso actual: {}, Uso nuevo: {}, Capacidad: {}",
                                usoActual, usoNuevo, capacidadPool);
                        throw new IllegalArgumentException("No hay disponibilidad en el pool para armar esta cancha");
                    }
                    log.debug("Validación de pool: OK (Uso: {}/{} disponibles)", usoActual + usoNuevo, capacidadPool);
                }
            }
        }
    }

    /**
     * Lista las reservas de una cancha en una fecha dada.
     * Restringido al dueño del establecimiento o a un administrador: expone nombre e
     * identidad de los jugadores, por lo que no debe quedar accesible a cualquier usuario autenticado.
     */
    @Transactional(readOnly = true)
    public Page<ReservaResponse> obtenerReservasPorCanchaYFecha(Long canchaId, LocalDate fecha, Pageable pageable, String email) {
        Cancha cancha = buscarCanchaPorId(canchaId);
        validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(LocalTime.MAX);
        return reservaRepository.findReservasEnRangoDiario(canchaId, inicioDia, finDia, EstadoReserva.CANCELADA, capPageSize(pageable))
                .map(reservaMapper::mapToResponse);
    }

    /**
     * Lista las reservas de un establecimiento en una fecha dada.
     * Restringido al dueño del establecimiento o a un administrador (ver justificación arriba).
     */
    @Transactional(readOnly = true)
    public Page<ReservaResponse> obtenerReservasPorEstablecimientoYFecha(Long estId, LocalDate fecha, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(estId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        validarPropietarioOAdmin(establecimiento, email);

        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(23, 59, 59);
        return reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNot(
                        estId, inicioDia, finDia, EstadoReserva.CANCELADA, capPageSize(pageable))
                .map(reservaMapper::mapToResponse);
    }

    private void validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = buscarUsuarioPorEmail(email);
        boolean esAdmin = usuarioAutenticado.getRol() == Role.ADMIN;
        boolean esDueno = establecimiento.getDueno().getId().equals(usuarioAutenticado.getId());
        if (!esAdmin && !esDueno) {
            throw new AccessDeniedException("No autorizado para ver las reservas de este establecimiento");
        }
    }

    private Pageable capPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= TAMANIO_PAGINA_MAXIMO) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), TAMANIO_PAGINA_MAXIMO, pageable.getSort());
    }
}
