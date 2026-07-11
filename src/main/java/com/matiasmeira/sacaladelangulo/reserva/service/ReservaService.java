package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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
    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReservaMapper reservaMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;

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
        validarDeporteSoportado(request.deporteSeleccionado(), cancha);

        validarFechas(request);
        validarGranularidadHoraria(request, cancha);
        long duracionMinutos = validarDuracion(request, cancha);
        validarSinBloqueos(request, cancha);
        validarDiaNoLaborable(request.fechaHoraInicio(), cancha.getEstablecimiento());
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
                .deporteSeleccionado(request.deporteSeleccionado())
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

    /**
     * Crea una reserva de mostrador para un cliente presencial/telefónico sin cuenta
     * en la plataforma. Solo puede utilizarla el dueño real del establecimiento (o un
     * administrador) al que pertenece la cancha solicitada. Nace directamente en estado
     * CONFIRMADA, ya que la registra el propio dueño.
     *
     * @param request DTO con los datos de la reserva manual y del cliente
     * @param email Email del usuario autenticado (OWNER/ADMIN)
     * @return ReservaResponse con los datos de la reserva creada
     */
    public ReservaResponse crearReservaManual(ReservaManualRequest request, String email) {
        log.info("Iniciando creación de reserva manual. Email: {}, Cancha: {}, Inicio: {}, Fin: {}",
                email, request.canchaId(), request.fechaHoraInicio(), request.fechaHoraFin());

        Cancha cancha = buscarCanchaPorId(request.canchaId());
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                cancha.getEstablecimiento(), email, PermisoEmpleado.CREAR_RESERVA_MANUAL);

        try {
            validarDeporteSoportado(request.deporteSeleccionado(), cancha);

            validarFechas(request.fechaHoraInicio(), request.fechaHoraFin());
            validarGranularidadHoraria(request.fechaHoraInicio(), cancha);
            long duracionMinutos = validarDuracion(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
            validarSinBloqueos(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
            validarDiaNoLaborable(request.fechaHoraInicio(), cancha.getEstablecimiento());
            validarHorarioAtencion(request.fechaHoraInicio(), request.fechaHoraFin(), cancha.getEstablecimiento());

            List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                    cancha.getEstablecimiento().getId(),
                    request.fechaHoraInicio(),
                    request.fechaHoraFin()
            );
            log.debug("Se encontraron {} reservas solapadas en el predio", solapadas.size());

            validarCanchaExactaLibre(cancha, solapadas);
            validarPoolCanchas(cancha, solapadas);

            BigDecimal precioCalculado = calcularPrecio(cancha, request.fechaHoraInicio(), duracionMinutos);
            BigDecimal senaPagada = Boolean.TRUE.equals(request.senaFisicaRecibida()) ? cancha.getMontoSena() : BigDecimal.ZERO;

            Reserva reserva = Reserva.builder()
                    .jugador(null)
                    .cancha(cancha)
                    .deporteSeleccionado(request.deporteSeleccionado())
                    .nombreClienteManual(request.nombreCliente())
                    .telefonoClienteManual(request.telefonoCliente())
                    .fechaHoraInicio(request.fechaHoraInicio())
                    .fechaHoraFin(request.fechaHoraFin())
                    .estado(EstadoReserva.CONFIRMADA)
                    .precioTotal(precioCalculado)
                    .senaPagada(senaPagada)
                    .build();

            Reserva reservaGuardada = reservaRepository.save(reserva);
            log.info("Nueva reserva manual creada con éxito. ID: {}, Cancha: {}, Cliente: {}",
                    reservaGuardada.getId(), cancha.getNombre(), request.nombreCliente());

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.CREAR_RESERVA_MANUAL,
                    reservaGuardada.getId(), true, "Reserva manual creada para " + request.nombreCliente());
            return reservaMapper.mapToResponse(reservaGuardada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.CREAR_RESERVA_MANUAL, null, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Crea un turno fijo semanal: genera una Reserva individual por cada fecha del
     * período que coincida con el día de la semana solicitado, en el mismo horario.
     * La operación es todo-o-nada: si una sola fecha no tiene disponibilidad (choca con
     * otra reserva, un bloqueo por mantenimiento, o cae fuera del horario de atención),
     * no se persiste ninguna reserva. Solo puede utilizarla el dueño real del
     * establecimiento al que pertenece la cancha. Todas las reservas generadas nacen en
     * estado CONFIRMADA, ya que las registra el propio dueño.
     *
     * @param request DTO con el rango de fechas, el día/horario recurrente y los datos del cliente
     * @param email Email del usuario autenticado (OWNER)
     * @return Lista de ReservaResponse con cada ocurrencia creada
     */
    public List<ReservaResponse> crearReservaSemanal(ReservaSemanalRequest request, String email) {
        log.info("Iniciando creación de reserva semanal. Email: {}, Cancha: {}, Día: {}, Horario: {}-{}, Período: {} a {}",
                email, request.canchaId(), request.diaSemana(), request.horaInicio(), request.horaFin(),
                request.fechaInicioPeriodo(), request.fechaFinPeriodo());

        if (request.fechaInicioPeriodo().isAfter(request.fechaFinPeriodo())) {
            throw new IllegalArgumentException("La fecha de inicio del período no puede ser posterior a la fecha de fin del período");
        }
        if (!request.horaInicio().isBefore(request.horaFin())) {
            throw new IllegalArgumentException("La hora de inicio debe ser anterior a la hora de fin");
        }

        Cancha cancha = buscarCanchaPorId(request.canchaId());
        validarPropietarioOAdmin(cancha.getEstablecimiento(), email);
        validarDeporteSoportado(request.deporteSeleccionado(), cancha);

        Usuario jugador = null;
        if (request.jugadorId() != null) {
            jugador = usuarioRepository.findById(request.jugadorId())
                    .orElseThrow(() -> new EntityNotFoundException("Jugador no encontrado"));
        } else if (request.nombreClienteManual() == null || request.nombreClienteManual().isBlank()) {
            throw new IllegalArgumentException("Debe indicar un jugador registrado (jugadorId) o el nombre del cliente (nombreClienteManual)");
        }

        List<LocalDate> fechasDelPeriodo = generarFechasDelPeriodo(
                request.fechaInicioPeriodo(), request.fechaFinPeriodo(), request.diaSemana());
        if (fechasDelPeriodo.isEmpty()) {
            throw new IllegalArgumentException("El período indicado no contiene ningún " + request.diaSemana());
        }

        List<Reserva> reservasAGuardar = new ArrayList<>();
        for (LocalDate fecha : fechasDelPeriodo) {
            LocalDateTime inicio = fecha.atTime(request.horaInicio());
            LocalDateTime fin = fecha.atTime(request.horaFin());

            try {
                validarFechas(inicio, fin);
                validarGranularidadHoraria(inicio, cancha);
                long duracionMinutos = validarDuracion(inicio, fin, cancha);
                validarSinBloqueos(inicio, fin, cancha);
                validarDiaNoLaborable(inicio, cancha.getEstablecimiento());
                validarHorarioAtencion(inicio, fin, cancha.getEstablecimiento());

                List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                        cancha.getEstablecimiento().getId(), inicio, fin);
                validarCanchaExactaLibre(cancha, solapadas);
                validarPoolCanchas(cancha, solapadas);

                BigDecimal precioCalculado = calcularPrecio(cancha, inicio, duracionMinutos);

                reservasAGuardar.add(Reserva.builder()
                        .jugador(jugador)
                        .cancha(cancha)
                        .deporteSeleccionado(request.deporteSeleccionado())
                        .nombreClienteManual(jugador == null ? request.nombreClienteManual() : null)
                        .telefonoClienteManual(jugador == null ? request.telefonoClienteManual() : null)
                        .fechaHoraInicio(inicio)
                        .fechaHoraFin(fin)
                        .estado(EstadoReserva.CONFIRMADA)
                        .precioTotal(precioCalculado)
                        .senaPagada(BigDecimal.ZERO)
                        .build());
            } catch (IllegalArgumentException ex) {
                log.warn("Reserva semanal rechazada en la fecha {}: {}", fecha, ex.getMessage());
                throw new IllegalArgumentException("No se pudo crear el turno fijo para el " + fecha + ": " + ex.getMessage());
            }
        }

        List<Reserva> reservasGuardadas = reservaRepository.saveAll(reservasAGuardar);
        log.info("Turno fijo creado con éxito. {} reservas generadas para la cancha {}",
                reservasGuardadas.size(), cancha.getNombre());

        return reservasGuardadas.stream().map(reservaMapper::mapToResponse).toList();
    }

    /**
     * Genera las fechas del período que coinciden con el día de la semana indicado,
     * avanzando de a una semana desde la primera ocurrencia dentro del rango.
     */
    private List<LocalDate> generarFechasDelPeriodo(LocalDate fechaInicioPeriodo, LocalDate fechaFinPeriodo, DayOfWeek diaSemana) {
        List<LocalDate> fechas = new ArrayList<>();
        LocalDate fecha = fechaInicioPeriodo.with(TemporalAdjusters.nextOrSame(diaSemana));
        while (!fecha.isAfter(fechaFinPeriodo)) {
            fechas.add(fecha);
            fecha = fecha.plusWeeks(1);
        }
        return fechas;
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
        validarFechas(request.fechaHoraInicio(), request.fechaHoraFin());
    }

    private void validarFechas(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin) {
        if (!fechaHoraInicio.isBefore(fechaHoraFin)) {
            log.warn("Fechas inválidas. Inicio >= Fin");
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }
        if (fechaHoraInicio.isBefore(LocalDateTime.now())) {
            log.warn("Reserva solicitada en el pasado");
            throw new IllegalArgumentException("No se pueden crear reservas en el pasado");
        }
    }

    private void validarGranularidadHoraria(ReservaRequest request, Cancha cancha) {
        validarGranularidadHoraria(request.fechaHoraInicio(), cancha);
    }

    private void validarGranularidadHoraria(LocalDateTime fechaHoraInicio, Cancha cancha) {
        int minutoInicio = fechaHoraInicio.getMinute();
        if (minutoInicio != 0 && minutoInicio != 30) {
            log.warn("Inicio de reserva inválido: {}", fechaHoraInicio);
            throw new IllegalArgumentException("Las reservas solo pueden iniciar en punto (:00) o y media (:30)");
        }
        if (Boolean.FALSE.equals(cancha.getPermiteInicioMediaHora()) && minutoInicio != 0) {
            log.warn("Cancha no permite inicio a media hora: {}", fechaHoraInicio);
            throw new IllegalArgumentException("Esta cancha solo permite iniciar reservas en horas en punto exactas (:00)");
        }
    }

    private long validarDuracion(ReservaRequest request, Cancha cancha) {
        return validarDuracion(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
    }

    private long validarDuracion(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Cancha cancha) {
        long duracionMinutos = Duration.between(fechaHoraInicio, fechaHoraFin).toMinutes();
        if (!cancha.getDuracionesPermitidas().contains((int) duracionMinutos)) {
            log.warn("Duración no permitida: {} minutos", duracionMinutos);
            throw new IllegalArgumentException("Duración no permitida. Opciones válidas: " + cancha.getDuracionesPermitidas() + " minutos");
        }
        return duracionMinutos;
    }

    private void validarSinBloqueos(ReservaRequest request, Cancha cancha) {
        validarSinBloqueos(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
    }

    private void validarSinBloqueos(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Cancha cancha) {
        List<BloqueoCancha> bloqueos = bloqueoCanchaRepository.findOverlappingBloqueos(
                cancha.getId(), fechaHoraInicio, fechaHoraFin);
        if (!bloqueos.isEmpty()) {
            throw new IllegalArgumentException("La cancha se encuentra bloqueada en ese horario. Motivo: " + bloqueos.get(0).getMotivo());
        }
    }

    /**
     * La cancha debe estar habilitada para el deporte solicitado: una misma cancha
     * puede soportar varios deportes (ej. fútbol y hockey), pero solo se puede
     * reservar para uno de los que tiene realmente habilitados.
     */
    private void validarDeporteSoportado(Deporte deporteSeleccionado, Cancha cancha) {
        if (!cancha.getDeportes().contains(deporteSeleccionado)) {
            log.warn("Deporte no soportado por la cancha. Cancha: {}, Deporte solicitado: {}", cancha.getId(), deporteSeleccionado);
            throw new IllegalArgumentException("La cancha no está habilitada para el deporte " + deporteSeleccionado);
        }
    }

    /**
     * Excepción puntual al horario semanal recurrente: si la fecha cae en un
     * DiaNoLaborable del establecimiento (feriado, cierre especial), se rechaza
     * independientemente de lo que indique el HorarioAtencion del día de esa semana.
     */
    private void validarDiaNoLaborable(LocalDateTime fechaHoraInicio, Establecimiento establecimiento) {
        diaNoLaborableRepository.findByEstablecimientoIdAndFecha(establecimiento.getId(), fechaHoraInicio.toLocalDate())
                .ifPresent(diaNoLaborable -> {
                    String motivo = diaNoLaborable.getMotivo();
                    String detalle = (motivo == null || motivo.isBlank()) ? "" : ". Motivo: " + motivo;
                    log.warn("El establecimiento {} no abre el {}", establecimiento.getId(), fechaHoraInicio.toLocalDate());
                    throw new IllegalArgumentException(
                            "El establecimiento no abre el " + fechaHoraInicio.toLocalDate() + detalle);
                });
    }

    private void validarHorarioAtencion(ReservaRequest request, Establecimiento establecimiento) {
        validarHorarioAtencion(request.fechaHoraInicio(), request.fechaHoraFin(), establecimiento);
    }

    private void validarHorarioAtencion(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Establecimiento establecimiento) {
        DayOfWeek diaSemana = fechaHoraInicio.getDayOfWeek();
        LocalTime horaInicio = fechaHoraInicio.toLocalTime();
        LocalTime horaFin = fechaHoraFin.toLocalTime();

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
        return calcularPrecio(cancha, request.fechaHoraInicio(), duracionMinutos);
    }

    private BigDecimal calcularPrecio(Cancha cancha, LocalDateTime fechaHoraInicio, long duracionMinutos) {
        BigDecimal duracionHoras = BigDecimal.valueOf(duracionMinutos)
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        BigDecimal precioPorHora = cancha.getPrecioBase();
        for (var tarifa : cancha.getTarifas()) {
            if (tarifa.getDiaSemana() == fechaHoraInicio.getDayOfWeek() &&
                    !fechaHoraInicio.toLocalTime().isBefore(tarifa.getHoraInicio()) &&
                    fechaHoraInicio.toLocalTime().isBefore(tarifa.getHoraFin())) {
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
        boolean esElJugador = reserva.getJugador() != null && reserva.getJugador().getId().equals(usuarioAutenticado.getId());
        boolean esEmpleadoAutorizado = autorizacionEmpleadoService.tienePermiso(
                usuarioAutenticado, reserva.getCancha().getEstablecimiento(), PermisoEmpleado.CANCELAR_RESERVA);

        if (!esAdmin && !esDuenoDelEstablecimiento && !esElJugador && !esEmpleadoAutorizado) {
            log.warn("Acceso denegado a cancelar reserva. Usuario: {}, Dueño: {}", usuarioAutenticado.getId(), duenioEstablecimientoId);
            throw new AccessDeniedException("No está autorizado para cancelar esta reserva");
        }

        try {
            if (esElJugador && !esAdmin && !esDuenoDelEstablecimiento && !esEmpleadoAutorizado) {
                validarPlazoDeCancelacion(reserva);
            }

            if (reserva.getEstado() == EstadoReserva.CANCELADA) {
                log.info("Reserva ya se encuentra cancelada. ID: {}", reservaId);
                return reservaMapper.mapToResponse(reserva);
            }

            reserva.setEstado(EstadoReserva.CANCELADA);
            Reserva reservaActualizada = reservaRepository.save(reserva);
            log.info("Reserva cancelada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.CANCELAR_RESERVA, reservaId, true, "Reserva cancelada");
            return reservaMapper.mapToResponse(reservaActualizada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.CANCELAR_RESERVA, reservaId, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Finaliza una reserva (el turno ya se jugó). Solo el dueño real del
     * establecimiento, un administrador, o un empleado con el permiso
     * FINALIZAR_RESERVA pueden hacerlo. Es idempotente: si ya estaba finalizada,
     * no hace nada.
     *
     * @param reservaId ID de la reserva a finalizar
     * @param email Email del usuario autenticado
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse finalizarReserva(Long reservaId, String email) {
        log.info("Iniciando finalización de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                reserva.getCancha().getEstablecimiento(), email, PermisoEmpleado.FINALIZAR_RESERVA);

        try {
            if (reserva.getEstado() == EstadoReserva.CANCELADA) {
                throw new IllegalArgumentException("No se puede finalizar una reserva cancelada");
            }

            if (reserva.getEstado() == EstadoReserva.FINALIZADA) {
                log.info("Reserva ya se encontraba finalizada. ID: {}", reservaId);
                return reservaMapper.mapToResponse(reserva);
            }

            reserva.setEstado(EstadoReserva.FINALIZADA);
            Reserva reservaActualizada = reservaRepository.save(reserva);
            log.info("Reserva finalizada con éxito. ID: {}", reservaId);

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.FINALIZAR_RESERVA, reservaId, true, "Reserva finalizada");
            return reservaMapper.mapToResponse(reservaActualizada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.FINALIZAR_RESERVA, reservaId, false, ex.getMessage());
            throw ex;
        }
    }

    private void registrarAuditoriaSiEsEmpleado(Usuario usuario, PermisoEmpleado accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
        if (usuario.getRol() == Role.EMPLOYEE) {
            registroAuditoriaService.registrar(usuario, accion, entidadAfectadaId, exitoso, detalle);
        }
    }

    /**
     * Reasigna una reserva existente a otra cancha del mismo establecimiento (por ejemplo,
     * para reubicarla cuando la cancha original quedó bloqueada por mantenimiento y hay una
     * cancha equivalente libre en ese horario). Solo el dueño real del establecimiento o un
     * administrador pueden moverla. Se revalidan las mismas reglas de disponibilidad que al
     * crear una reserva (granularidad, duración, bloqueos, solapamientos y pool de canchas);
     * no se revalida el horario de atención porque la nueva cancha pertenece al mismo
     * establecimiento, ya validado cuando se creó la reserva original.
     *
     * @param reservaId ID de la reserva a mover
     * @param nuevaCanchaId ID de la cancha destino
     * @param email Email del usuario autenticado (OWNER/ADMIN)
     * @return ReservaResponse con la reserva ya reasignada
     */
    public ReservaResponse moverReservaDeCancha(Long reservaId, Long nuevaCanchaId, String email) {
        log.info("Iniciando movimiento de reserva. ID: {}, Nueva cancha: {}, Email: {}", reservaId, nuevaCanchaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        validarPropietarioOAdmin(reserva.getCancha().getEstablecimiento(), email);

        if (reserva.getEstado() == EstadoReserva.CANCELADA) {
            throw new IllegalArgumentException("No se puede mover una reserva cancelada");
        }

        Cancha nuevaCancha = buscarCanchaPorId(nuevaCanchaId);
        if (!nuevaCancha.getEstablecimiento().getId().equals(reserva.getCancha().getEstablecimiento().getId())) {
            throw new IllegalArgumentException("La nueva cancha debe pertenecer al mismo establecimiento que la reserva original");
        }
        if (nuevaCancha.getId().equals(reserva.getCancha().getId())) {
            throw new IllegalArgumentException("La reserva ya está asignada a esa cancha");
        }
        validarDeporteSoportado(reserva.getDeporteSeleccionado(), nuevaCancha);

        LocalDateTime inicio = reserva.getFechaHoraInicio();
        LocalDateTime fin = reserva.getFechaHoraFin();

        validarGranularidadHoraria(inicio, nuevaCancha);
        validarDuracion(inicio, fin, nuevaCancha);
        validarSinBloqueos(inicio, fin, nuevaCancha);

        List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                        nuevaCancha.getEstablecimiento().getId(), inicio, fin).stream()
                .filter(r -> !r.getId().equals(reserva.getId()))
                .toList();
        validarCanchaExactaLibre(nuevaCancha, solapadas);
        validarPoolCanchas(nuevaCancha, solapadas);

        reserva.setCancha(nuevaCancha);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Reserva {} movida con éxito a la cancha {}", reservaId, nuevaCancha.getNombre());

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
