package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.exception.JugadorBloqueadoException;
import com.matiasmeira.sacaladelangulo.core.exception.ReservaExpiradaException;
import com.matiasmeira.sacaladelangulo.core.exception.TelefonoNoVerificadoException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.service.PoolCanchaCalculator;
import com.matiasmeira.sacaladelangulo.establecimiento.service.PrecioReservaCalculator;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

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
    /**
     * Mismo tope que DisponibilidadService.RANGO_MAXIMO_DIAS: no tiene sentido permitir
     * reservar más adelante de lo que la propia grilla de disponibilidad puede mostrar.
     * Solo aplica a reservas puntuales (crearReserva/crearReservaManual); TurnoFijoService.crear
     * queda afuera a propósito, ya que existe justamente para turnos fijos de largo plazo.
     */
    private static final int LIMITE_ANTICIPACION_DIAS = 31;

    /**
     * Ventana de gracia para confirmar/pagar la seña de una reserva de jugador antes de
     * que se libere automáticamente (ver crearReserva, confirmarReserva y
     * ReservaExpiracionService).
     */
    private static final long TIEMPO_EXPIRACION_MINUTOS = 10;

    /**
     * Estados que no deben contarse como "reserva activa" en los listados por defecto
     * (incluirCanceladas=false): tanto una cancelación explícita como una liberación
     * automática por vencimiento de la ventana de 10 minutos.
     */
    private static final List<EstadoReserva> ESTADOS_CANCELADOS =
            List.of(EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA);


    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final BloqueoJugadorRepository bloqueoJugadorRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ReservaMapper reservaMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ApplicationEventPublisher eventPublisher;
    private final TurnoCajaService turnoCajaService;

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

        if (bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(cancha.getEstablecimiento().getId(), jugador.getId())) {
            log.warn("Jugador bloqueado intentó reservar. Jugador: {}, Establecimiento: {}", jugador.getId(), cancha.getEstablecimiento().getId());
            throw new JugadorBloqueadoException("No tenés permitido realizar reservas en este establecimiento");
        }

        if (Boolean.TRUE.equals(cancha.getEstablecimiento().getRequiereTelefonoVerificado())
                && !Boolean.TRUE.equals(jugador.getTelefonoVerificado())) {
            log.warn("Jugador sin teléfono verificado intentó reservar. Jugador: {}, Establecimiento: {}", jugador.getId(), cancha.getEstablecimiento().getId());
            throw new TelefonoNoVerificadoException("Este establecimiento requiere que verifiques tu teléfono antes de reservar");
        }

        validarDeporteSoportado(request.deporteSeleccionado(), cancha);

        validarFechas(request);
        validarLimiteDeAnticipacion(request.fechaHoraInicio());
        validarGranularidadHoraria(request, cancha);
        long duracionMinutos = validarDuracion(request, cancha);
        validarSinBloqueos(request, cancha);
        validarDiaNoLaborable(request.fechaHoraInicio(), cancha.getEstablecimiento());
        validarHorarioAtencion(request, cancha.getEstablecimiento());

        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(cancha.getEstablecimiento().getId());
        bloquearCanchasRelacionadas(cancha, todasLasCanchas);

        List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                cancha.getEstablecimiento().getId(),
                request.fechaHoraInicio(),
                request.fechaHoraFin(),
                LocalDateTime.now()
        );
        log.debug("Se encontraron {} reservas solapadas en el predio", solapadas.size());

        validarCanchaExactaLibre(cancha, solapadas);
        validarPoolCanchas(cancha, solapadas, todasLasCanchas);

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
                .expiraEn(LocalDateTime.now().plusMinutes(TIEMPO_EXPIRACION_MINUTOS))
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
            validarLimiteDeAnticipacion(request.fechaHoraInicio());
            validarGranularidadHoraria(request.fechaHoraInicio(), cancha);
            long duracionMinutos = validarDuracion(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
            validarSinBloqueos(request.fechaHoraInicio(), request.fechaHoraFin(), cancha);
            validarDiaNoLaborable(request.fechaHoraInicio(), cancha.getEstablecimiento());
            validarHorarioAtencion(request.fechaHoraInicio(), request.fechaHoraFin(), cancha.getEstablecimiento());

            List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(cancha.getEstablecimiento().getId());
            bloquearCanchasRelacionadas(cancha, todasLasCanchas);

            List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                    cancha.getEstablecimiento().getId(),
                    request.fechaHoraInicio(),
                    request.fechaHoraFin(),
                    LocalDateTime.now()
            );
            log.debug("Se encontraron {} reservas solapadas en el predio", solapadas.size());

            validarCanchaExactaLibre(cancha, solapadas);
            validarPoolCanchas(cancha, solapadas, todasLasCanchas);

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
            eventPublisher.publishEvent(new ReservaConfirmadaEvent(reservaGuardada.getId()));

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CREAR_RESERVA_MANUAL,
                    reservaGuardada.getId(), true, "Reserva manual creada para " + request.nombreCliente());
            return reservaMapper.mapToResponse(reservaGuardada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CREAR_RESERVA_MANUAL, null, false, ex.getMessage());
            throw ex;
        }
    }

    private Usuario buscarUsuarioPorEmail(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        log.debug("Usuario encontrado: {}", usuario.getId());
        return usuario;
    }

    Cancha buscarCanchaPorId(Long canchaId) {
        Cancha cancha = canchaRepository.findById(canchaId)
                .orElseThrow(() -> new EntityNotFoundException("Cancha no encontrada"));
        log.debug("Cancha encontrada: {} - Establecimiento: {}", cancha.getId(), cancha.getEstablecimiento().getId());
        return cancha;
    }

    private void validarFechas(ReservaRequest request) {
        validarFechas(request.fechaHoraInicio(), request.fechaHoraFin());
    }

    void validarFechas(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin) {
        if (!fechaHoraInicio.isBefore(fechaHoraFin)) {
            log.warn("Fechas inválidas. Inicio >= Fin");
            throw new IllegalArgumentException("La fecha de inicio debe ser anterior a la de fin");
        }
        if (fechaHoraInicio.isBefore(LocalDateTime.now())) {
            log.warn("Reserva solicitada en el pasado");
            throw new IllegalArgumentException("No se pueden crear reservas en el pasado");
        }
    }

    /**
     * Cota superior de anticipación para reservas puntuales (ver LIMITE_ANTICIPACION_DIAS).
     * No se llama desde TurnoFijoService.crear a propósito.
     */
    private void validarLimiteDeAnticipacion(LocalDateTime fechaHoraInicio) {
        if (ChronoUnit.DAYS.between(LocalDateTime.now(), fechaHoraInicio) >= LIMITE_ANTICIPACION_DIAS) {
            log.warn("Reserva solicitada con demasiada anticipación: {}", fechaHoraInicio);
            throw new IllegalArgumentException(
                    "No se pueden crear reservas con más de " + LIMITE_ANTICIPACION_DIAS + " días de anticipación");
        }
    }

    private void validarGranularidadHoraria(ReservaRequest request, Cancha cancha) {
        validarGranularidadHoraria(request.fechaHoraInicio(), cancha);
    }

    void validarGranularidadHoraria(LocalDateTime fechaHoraInicio, Cancha cancha) {
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

    long validarDuracion(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Cancha cancha) {
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
     * Misma validación que {@link #validarSinBloqueos(LocalDateTime, LocalDateTime, Cancha)},
     * pero filtrando en memoria una lista de bloqueos ya precargada para el rango completo
     * (ver TurnoFijoService.crear), en vez de consultar la base de datos por cada ocurrencia.
     */
    void validarSinBloqueos(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Cancha cancha, List<BloqueoCancha> bloqueosPrecargados) {
        Optional<BloqueoCancha> bloqueo = bloqueosPrecargados.stream()
                .filter(b -> b.getCancha().getId().equals(cancha.getId()))
                .filter(b -> seSuperponen(b.getFechaInicio(), b.getFechaFin(), fechaHoraInicio, fechaHoraFin))
                .findFirst();
        if (bloqueo.isPresent()) {
            throw new IllegalArgumentException("La cancha se encuentra bloqueada en ese horario. Motivo: " + bloqueo.get().getMotivo());
        }
    }

    /**
     * La cancha debe estar habilitada para el deporte solicitado: una misma cancha
     * puede soportar varios deportes (ej. fútbol y hockey), pero solo se puede
     * reservar para uno de los que tiene realmente habilitados.
     */
    void validarDeporteSoportado(Deporte deporteSeleccionado, Cancha cancha) {
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

    /**
     * Misma validación que {@link #validarDiaNoLaborable(LocalDateTime, Establecimiento)},
     * pero filtrando en memoria una lista de días no laborables ya precargada para el rango
     * completo (ver TurnoFijoService.crear), en vez de consultar la base de datos por cada
     * ocurrencia.
     */
    void validarDiaNoLaborable(LocalDateTime fechaHoraInicio, Establecimiento establecimiento, List<DiaNoLaborable> diasNoLaborablesPrecargados) {
        diasNoLaborablesPrecargados.stream()
                .filter(d -> d.getFecha().equals(fechaHoraInicio.toLocalDate()))
                .findFirst()
                .ifPresent(diaNoLaborable -> {
                    String motivo = diaNoLaborable.getMotivo();
                    String detalle = (motivo == null || motivo.isBlank()) ? "" : ". Motivo: " + motivo;
                    log.warn("El establecimiento {} no abre el {}", establecimiento.getId(), fechaHoraInicio.toLocalDate());
                    throw new IllegalArgumentException(
                            "El establecimiento no abre el " + fechaHoraInicio.toLocalDate() + detalle);
                });
    }

    boolean seSuperponen(LocalDateTime inicioA, LocalDateTime finA, LocalDateTime inicioB, LocalDateTime finB) {
        return inicioA.isBefore(finB) && finA.isAfter(inicioB);
    }

    private void validarHorarioAtencion(ReservaRequest request, Establecimiento establecimiento) {
        validarHorarioAtencion(request.fechaHoraInicio(), request.fechaHoraFin(), establecimiento);
    }

    void validarHorarioAtencion(LocalDateTime fechaHoraInicio, LocalDateTime fechaHoraFin, Establecimiento establecimiento) {
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

    void validarCanchaExactaLibre(Cancha cancha, List<Reserva> solapadas) {
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

    BigDecimal calcularPrecio(Cancha cancha, LocalDateTime fechaHoraInicio, long duracionMinutos) {
        return PrecioReservaCalculator.calcularPrecio(cancha, fechaHoraInicio, duracionMinutos);
    }

    /**
     * Confirma una reserva y cambia su estado a CONFIRMADA. Solo se permite la
     * transición PENDIENTE_SENA -> CONFIRMADA; es idempotente si ya estaba CONFIRMADA
     * (no-op), y rechaza confirmar una reserva CANCELADA o FINALIZADA para no "resucitarla"
     * sin revalidar solapamientos/pool. Solo el dueño del establecimiento o un
     * administrador puede confirmar.
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

        if (reserva.getEstado() == EstadoReserva.CONFIRMADA) {
            log.info("Reserva ya se encontraba confirmada. ID: {}", reservaId);
            return reservaMapper.mapToResponse(reserva);
        }

        if (reserva.getEstado() != EstadoReserva.PENDIENTE_SENA) {
            log.warn("Intento de confirmar una reserva en estado no válido. ID: {}, Estado: {}", reservaId, reserva.getEstado());
            throw new IllegalArgumentException(
                    "Solo se puede confirmar una reserva en estado PENDIENTE_SENA (actual: " + reserva.getEstado() + ")");
        }

        if (reserva.getExpiraEn() != null && reserva.getExpiraEn().isBefore(LocalDateTime.now())) {
            log.warn("Intento de confirmar una reserva ya vencida. ID: {}, Venció: {}", reservaId, reserva.getExpiraEn());
            throw new ReservaExpiradaException(
                    "La ventana de 10 minutos para confirmar esta reserva ya venció. Debe realizar una nueva reserva.");
        }

        reserva.setEstado(EstadoReserva.CONFIRMADA);
        reserva.setExpiraEn(null);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Reserva confirmada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());
        eventPublisher.publishEvent(new ReservaConfirmadaEvent(reservaActualizada.getId()));

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

            if (reserva.getEstado() == EstadoReserva.FINALIZADA) {
                throw new IllegalArgumentException("No se puede cancelar una reserva ya finalizada");
            }

            // AUSENTE es terminal (ver EstadoReserva): sin este chequeo caía al setEstado de
            // abajo y pasaba a CANCELADA, borrando el registro del no-show. Y como el jugador
            // es actor autorizado de este método, era el propio ausente quien podía borrarlo.
            // La única salida legítima de AUSENTE es revertirAusencia, restringida a dueño/admin.
            if (reserva.getEstado() == EstadoReserva.AUSENTE) {
                throw new IllegalArgumentException(
                        "No se puede cancelar un turno marcado como ausente. El dueño del complejo puede "
                                + "deshacer la ausencia y recién entonces cancelarlo.");
            }

            // Los dos estados terminales de cancelación entran acá, no sólo CANCELADA:
            // una reserva ya liberada por vencimiento del hold (CANCELADA_PRERESERVA) caía
            // al setEstado de abajo y se sobreescribía como CANCELADA, borrando la
            // distinción entre "nadie confirmó a tiempo" y "alguien canceló", que
            // EstadoReserva documenta como deliberada y de la que dependen los reportes.
            if (ESTADOS_CANCELADOS.contains(reserva.getEstado())) {
                log.info("Reserva ya se encuentra cancelada. ID: {}, Estado: {}", reservaId, reserva.getEstado());
                return reservaMapper.mapToResponse(reserva);
            }

            reserva.setEstado(EstadoReserva.CANCELADA);
            Reserva reservaActualizada = reservaRepository.save(reserva);
            log.info("Reserva cancelada con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());
            eventPublisher.publishEvent(new ReservaCanceladaEvent(reservaActualizada.getId(), usuarioAutenticado.getId()));

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CANCELAR_RESERVA, reservaId, true, "Reserva cancelada");
            return reservaMapper.mapToResponse(reservaActualizada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CANCELAR_RESERVA, reservaId, false, ex.getMessage());
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
     * @param metodoPago Método de pago con el que se saldó la reserva
     * @param email Email del usuario autenticado
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse finalizarReserva(Long reservaId, MetodoPago metodoPago, String email) {
        log.info("Iniciando finalización de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                reserva.getCancha().getEstablecimiento(), email, PermisoEmpleado.FINALIZAR_RESERVA);

        try {
            // CANCELADA_PRERESERVA (venció la ventana de 10 min sin que nadie pagara la seña)
            // se rechaza igual que CANCELADA: sin este chequeo, una prereserva que nadie
            // confirmó podía "finalizarse" igual, generando cobro y movimiento de caja sobre
            // un turno que nunca fue confirmado (ver REVISION_FUNCIONAL.md).
            if (reserva.getEstado() == EstadoReserva.CANCELADA || reserva.getEstado() == EstadoReserva.CANCELADA_PRERESERVA) {
                throw new IllegalArgumentException("No se puede finalizar una reserva cancelada");
            }

            if (reserva.getEstado() == EstadoReserva.FINALIZADA) {
                log.info("Reserva ya se encontraba finalizada. ID: {}", reservaId);
                return reservaMapper.mapToResponse(reserva);
            }

            if (reserva.getEstado() == EstadoReserva.PENDIENTE_SENA) {
                // Mismo criterio de matriz de estados que confirmarReserva/cancelarReserva
                // (ver C2 y A3 en la auditoría): una reserva tiene que pasar por CONFIRMADA
                // antes de poder finalizarse.
                throw new IllegalArgumentException("No se puede finalizar una reserva que todavía no fue confirmada");
            }

            // Contracara del chequeo de CANCELADA_PRERESERVA de arriba, que quedó incompleto:
            // finalizar un no-show cobraba precioTotal - senaPagada y generaba movimiento de
            // caja por un turno que nadie jugó, contra lo que documenta marcarAusente ("no
            // hubo cobro ni servicio prestado"). Si en algún momento se quiere cobrar una
            // penalidad por ausencia, no puede salir por acá: el monto sería el del servicio
            // completo, no el de una multa.
            if (reserva.getEstado() == EstadoReserva.AUSENTE) {
                throw new IllegalArgumentException(
                        "No se puede finalizar un turno marcado como ausente. El dueño del complejo puede "
                                + "deshacer la ausencia si el jugador sí se presentó.");
            }

            reserva.setEstado(EstadoReserva.FINALIZADA);
            reserva.setMetodoPago(metodoPago);
            Reserva reservaActualizada = reservaRepository.save(reserva);
            log.info("Reserva finalizada con éxito. ID: {}", reservaId);

            BigDecimal montoCobrado = reservaActualizada.getPrecioTotal().subtract(reservaActualizada.getSenaPagada());
            if (montoCobrado.signum() > 0) {
                turnoCajaService.registrarMovimientoSiCorresponde(
                        reservaActualizada.getCancha().getEstablecimiento(), TipoMovimientoCaja.INGRESO,
                        OrigenMovimientoCaja.RESERVA, metodoPago, montoCobrado,
                        "Reserva #" + reservaId + " finalizada", reservaId, usuarioAutenticado);
            }

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.FINALIZAR_RESERVA, reservaId, true, "Reserva finalizada");
            return reservaMapper.mapToResponse(reservaActualizada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.FINALIZAR_RESERVA, reservaId, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Marca una reserva como AUSENTE (no-show): el turno estaba confirmado pero el
     * jugador no se presentó. Solo el dueño real del establecimiento, un administrador,
     * o un empleado con el permiso MARCAR_AUSENTE pueden hacerlo. Es idempotente: si ya
     * estaba AUSENTE, no hace nada. No genera movimiento de caja ni notificaciones: a
     * diferencia de finalizarReserva, acá no hubo cobro ni servicio prestado.
     *
     * @param reservaId ID de la reserva a marcar como ausente
     * @param email Email del usuario autenticado
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse marcarAusente(Long reservaId, String email) {
        log.info("Iniciando marcado de ausencia de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                reserva.getCancha().getEstablecimiento(), email, PermisoEmpleado.MARCAR_AUSENTE);

        try {
            if (reserva.getEstado() == EstadoReserva.AUSENTE) {
                log.info("Reserva ya se encontraba marcada como ausente. ID: {}", reservaId);
                return reservaMapper.mapToResponse(reserva);
            }

            if (reserva.getEstado() != EstadoReserva.CONFIRMADA) {
                throw new IllegalArgumentException(
                        "Solo se puede marcar como ausente una reserva en estado CONFIRMADA (actual: " + reserva.getEstado() + ")");
            }

            if (LocalDateTime.now().isBefore(reserva.getFechaHoraInicio())) {
                throw new IllegalArgumentException("No se puede marcar ausente un turno que todavía no empezó");
            }

            reserva.setEstado(EstadoReserva.AUSENTE);
            Reserva reservaActualizada = reservaRepository.save(reserva);
            log.info("Reserva marcada como ausente con éxito. ID: {}", reservaId);

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.MARCAR_AUSENTE, reservaId, true, "Reserva marcada como ausente");
            return reservaMapper.mapToResponse(reservaActualizada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.MARCAR_AUSENTE, reservaId, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Revierte una reserva marcada como AUSENTE de vuelta a CONFIRMADA. Es un override
     * sensible (corrige un error al marcar la ausencia), por lo que solo el dueño real
     * del establecimiento o un administrador pueden hacerlo, a diferencia de
     * marcarAusente: un empleado con el permiso MARCAR_AUSENTE no puede revertirlo.
     *
     * @param reservaId ID de la reserva a revertir
     * @param email Email del usuario autenticado (OWNER/ADMIN)
     * @return ReservaResponse con los datos actualizados
     */
    public ReservaResponse revertirAusencia(Long reservaId, String email) {
        log.info("Iniciando reversión de ausencia de reserva. ID: {}, Email: {}", reservaId, email);

        Reserva reserva = reservaRepository.findByIdConEstablecimientoYDueno(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));

        autorizacionEmpleadoService.validarPropietarioOAdmin(reserva.getCancha().getEstablecimiento(), email);

        if (reserva.getEstado() != EstadoReserva.AUSENTE) {
            throw new IllegalArgumentException("Solo se puede revertir un turno marcado como ausente");
        }

        reserva.setEstado(EstadoReserva.CONFIRMADA);
        Reserva reservaActualizada = reservaRepository.save(reserva);
        log.info("Ausencia revertida con éxito. ID: {}, Nuevo estado: {}", reservaId, reservaActualizada.getEstado());

        return reservaMapper.mapToResponse(reservaActualizada);
    }

    private void registrarAuditoriaSiEsEmpleado(Usuario usuario, AccionAuditoria accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
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

        autorizacionEmpleadoService.validarPropietarioOAdmin(reserva.getCancha().getEstablecimiento(), email);

        // Solo tiene sentido reasignar cancha en reservas todavía "en curso": una cancelada
        // no debería reflotarse, y una finalizada ya representa un partido que ya se jugó
        // en la cancha original (ver B6 en la auditoría).
        if (reserva.getEstado() != EstadoReserva.PENDIENTE_SENA && reserva.getEstado() != EstadoReserva.CONFIRMADA) {
            throw new IllegalArgumentException("Solo se puede mover una reserva pendiente de seña o confirmada");
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

        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(nuevaCancha.getEstablecimiento().getId());
        bloquearCanchasRelacionadas(nuevaCancha, todasLasCanchas);

        List<Reserva> solapadas = reservaRepository.findSuperpuestas(
                        nuevaCancha.getEstablecimiento().getId(), inicio, fin, LocalDateTime.now()).stream()
                .filter(r -> !r.getId().equals(reserva.getId()))
                .toList();
        validarCanchaExactaLibre(nuevaCancha, solapadas);
        validarPoolCanchas(nuevaCancha, solapadas, todasLasCanchas);

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
     * @param todasLasCanchasDelEstablecimiento canchas activas del establecimiento, ya
     *                                           precargadas por el llamador (evita repetir la query)
     */
    void validarPoolCanchas(Cancha cancha, List<Reserva> solapadas, List<Cancha> todasLasCanchasDelEstablecimiento) {
        if (!PoolCanchaCalculator.hayDisponibilidad(cancha, solapadas, todasLasCanchasDelEstablecimiento)) {
            log.warn("No hay disponibilidad en el pool. Cancha: {}", cancha.getId());
            throw new IllegalArgumentException("No hay disponibilidad en el pool para armar esta cancha");
        }
    }

    /**
     * Adquiere un lock pesimista (SELECT ... FOR UPDATE) sobre la cancha solicitada y toda
     * cancha relacionada por pool con ella, antes de validar solapamientos/disponibilidad
     * y persistir la reserva. Serializa entre sí a las transacciones concurrentes que
     * compiten por las mismas canchas, cerrando la condición de carrera que permitía
     * doble-booking (dos reservas solapadas creadas en simultáneo pasando ambas la
     * validación de "cancha libre").
     */
    void bloquearCanchasRelacionadas(Cancha cancha, List<Cancha> todasLasCanchasDelEstablecimiento) {
        List<Long> idsOrdenados = PoolCanchaCalculator.canchasRelacionadas(cancha, todasLasCanchasDelEstablecimiento)
                .stream()
                .sorted()
                .toList();
        canchaRepository.lockPorIds(idsOrdenados);
    }

    /**
     * Lista las reservas de una cancha en una fecha dada.
     * Restringido al dueño del establecimiento o a un administrador: expone nombre e
     * identidad de los jugadores, por lo que no debe quedar accesible a cualquier usuario autenticado.
     * Excluye canceladas por defecto; incluirCanceladas=true las trae también, para
     * auditar cancelaciones históricas (ver B7 en la auditoría).
     */
    @Transactional(readOnly = true)
    public Page<ReservaResponse> obtenerReservasPorCanchaYFecha(Long canchaId, LocalDate fecha, boolean incluirCanceladas, Pageable pageable, String email) {
        Cancha cancha = buscarCanchaPorId(canchaId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(cancha.getEstablecimiento(), email);

        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(LocalTime.MAX);
        Page<Reserva> reservas = incluirCanceladas
                ? reservaRepository.findReservasEnRangoDiarioIncluyendoCanceladas(canchaId, inicioDia, finDia, capPageSize(pageable))
                : reservaRepository.findReservasEnRangoDiario(canchaId, inicioDia, finDia, ESTADOS_CANCELADOS, capPageSize(pageable));
        return reservas.map(reservaMapper::mapToResponse);
    }

    /**
     * Lista las reservas de un establecimiento en una fecha dada.
     * Restringido al dueño del establecimiento o a un administrador (ver justificación arriba).
     * Excluye canceladas por defecto; incluirCanceladas=true las trae también (ver B7).
     */
    @Transactional(readOnly = true)
    public Page<ReservaResponse> obtenerReservasPorEstablecimientoYFecha(Long estId, LocalDate fecha, boolean incluirCanceladas, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(estId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarLectura(establecimiento, email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(23, 59, 59);
        Page<Reserva> reservas = incluirCanceladas
                ? reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(estId, inicioDia, finDia, capPageSize(pageable))
                : reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(
                        estId, inicioDia, finDia, ESTADOS_CANCELADOS, capPageSize(pageable));
        return reservas.map(reservaMapper::mapToResponse);
    }

    /**
     * Lista las reservas del jugador autenticado, opcionalmente filtradas por estado.
     */
    @Transactional(readOnly = true)
    public Page<ReservaResponse> obtenerMisReservas(String email, EstadoReserva estado, Pageable pageable) {
        Usuario jugador = buscarUsuarioPorEmail(email);
        Pageable pageableFinal = capPageSize(pageable);
        Page<Reserva> reservas = (estado == null)
                ? reservaRepository.findByJugadorId(jugador.getId(), pageableFinal)
                : reservaRepository.findByJugadorIdAndEstado(jugador.getId(), estado, pageableFinal);
        return reservas.map(reservaMapper::mapToResponse);
    }

    private Pageable capPageSize(Pageable pageable) {
        if (pageable.getPageSize() <= TAMANIO_PAGINA_MAXIMO) {
            return pageable;
        }
        return PageRequest.of(pageable.getPageNumber(), TAMANIO_PAGINA_MAXIMO, pageable.getSort());
    }
}
