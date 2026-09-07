package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.CancelacionTurnoFijoResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.EditarClienteTurnoFijoRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoListadoResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Servicio de negocio para turnos fijos semanales: la creación de la serie vivía en
 * ReservaService.crearReservaSemanal y generaba N reservas sueltas sin ningún registro que
 * las agrupara. Acá la creación persiste primero la REGLA (TurnoFijo) y después cada
 * ocurrencia (Reserva) apunta a ella, para que la serie sobreviva aunque se cancelen o
 * renueven sus reservas individuales.
 *
 * <p>Las validaciones de disponibilidad (horario de atención, bloqueos, pool de canchas,
 * etc.) son las mismas que usa una reserva puntual, y viven como package-private en
 * {@link ReservaService}: este servicio las reutiliza por inyección en vez de duplicarlas,
 * para que el turno fijo y la reserva puntual nunca discrepen sobre si un complejo abre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TurnoFijoService {

    /** Formato de fecha para los mensajes de error dirigidos al usuario. */
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Estados que una cancelación de serie nunca toca. */
    private static final Set<EstadoReserva> ESTADOS_INTOCABLES = Set.of(
            EstadoReserva.FINALIZADA, EstadoReserva.AUSENTE,
            EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA);

    private final TurnoFijoRepository turnoFijoRepository;
    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final UsuarioRepository usuarioRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final ReservaMapper reservaMapper;
    private final TurnoFijoMapper turnoFijoMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ReservaService reservaService;

    /**
     * Crea un turno fijo semanal nuevo. Delega en {@link #crearInterno} sin marca de
     * renovación: ver ese método para el detalle de la operación.
     *
     * @param request DTO con el rango de fechas, el día/horario recurrente y los datos del cliente
     * @param email Email del usuario autenticado (OWNER)
     * @return TurnoFijoResponse con la regla creada y cada ocurrencia generada
     */
    public TurnoFijoResponse crear(ReservaSemanalRequest request, String email) {
        return crearInterno(request, email, null);
    }

    /**
     * Vuelve a cargar la misma serie para el año siguiente al de su período, sin que el
     * dueño tenga que recargarla campo por campo. Crea una serie NUEVA (no muta la vieja) y
     * pasa por el mismo camino de creación: mismas validaciones, mismo lock, todo-o-nada.
     * Sólo se puede renovar una serie ACTIVA: una CANCELADA no vuelve a la vida por esta vía.
     *
     * <p>El inicio es max(1 de enero del año destino, hoy). El max con hoy es lo que hace
     * que renovar tarde — en febrero, no en enero — no falle contra @FutureOrPresent ni
     * contra validarFechas; generarFechasDelPeriodo ya busca la primera ocurrencia del día
     * pedido a partir de ahí.
     *
     * <p>Excepción al max de arriba: si "hoy" es justo el día de semana de la serie y su
     * horario de hoy ya pasó, arrancar en "hoy" es inválido igual —
     * generarFechasDelPeriodo toma "hoy" como primera ocurrencia porque nextOrSame no mira
     * la hora, y validarFechas la rechazaría por estar en el pasado, reventando las ~52
     * ocurrencias por esa única fecha (todo-o-nada). Ahí se arranca al día siguiente.
     *
     * @param id Id del turno fijo a renovar
     * @param email Email del usuario autenticado (OWNER o ADMIN)
     * @return TurnoFijoResponse con la serie nueva y sus ocurrencias
     */
    public TurnoFijoResponse renovar(Long id, String email) {
        TurnoFijo original = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(original.getCancha().getEstablecimiento(), email);

        if (original.getEstado() != EstadoTurnoFijo.ACTIVO) {
            throw new IllegalArgumentException("Este turno fijo está cancelado: no se puede renovar.");
        }

        // El índice único de V24 lo garantiza igual, pero sin este chequeo el segundo click
        // fallaría por solapamiento ("la cancha ya está reservada el 05/01"), que no le dice
        // nada al dueño.
        if (turnoFijoRepository.existsByRenovadoDesdeId(id)) {
            throw new IllegalArgumentException(
                    "Este turno fijo ya fue renovado. Buscá la serie del año siguiente en el listado.");
        }

        int anioDestino = original.getFechaFinPeriodo().getYear() + 1;
        LocalDate primeroDeEnero = LocalDate.of(anioDestino, 1, 1);
        LocalDate hoy = LocalDate.now();
        LocalDate inicio;
        if (primeroDeEnero.isAfter(hoy)) {
            inicio = primeroDeEnero;
        } else {
            boolean hoyEsElDiaYaPaso = hoy.getDayOfWeek() == original.getDiaSemana()
                    && !hoy.atTime(original.getHoraInicio()).isAfter(LocalDateTime.now());
            inicio = hoyEsElDiaYaPaso ? hoy.plusDays(1) : hoy;
        }

        ReservaSemanalRequest pedido = new ReservaSemanalRequest(
                original.getCancha().getId(),
                inicio,
                LocalDate.of(anioDestino, 12, 31),
                original.getDiaSemana(),
                original.getHoraInicio(),
                original.getHoraFin(),
                original.getDeporteSeleccionado(),
                original.getJugador() != null ? original.getJugador().getId() : null,
                original.getNombreClienteManual(),
                original.getTelefonoClienteManual());

        return crearInterno(pedido, email, id);
    }

    /**
     * Crea un turno fijo semanal: persiste la regla y genera una Reserva CONFIRMADA por
     * cada fecha del período que coincida con el día de la semana solicitado, en el mismo
     * horario, apuntando a esa regla. La operación es todo-o-nada: si una sola fecha no
     * tiene disponibilidad (choca con otra reserva, un bloqueo por mantenimiento, o cae
     * fuera del horario de atención), no se persiste ni la regla ni ninguna reserva. Solo
     * puede utilizarla el dueño real del establecimiento al que pertenece la cancha.
     *
     * <p>Compartido por {@link #crear} y {@link #renovar}: {@code renovadoDesdeId} viaja nulo
     * en una carga nueva y con el id de la serie original cuando la llamada viene de renovar,
     * para que ambos caminos validen y persistan exactamente igual.
     *
     * @param request DTO con el rango de fechas, el día/horario recurrente y los datos del cliente
     * @param email Email del usuario autenticado (OWNER)
     * @param renovadoDesdeId Id de la serie de la que esta es la renovación, o null si es una carga nueva
     * @return TurnoFijoResponse con la regla creada y cada ocurrencia generada
     */
    private TurnoFijoResponse crearInterno(ReservaSemanalRequest request, String email, Long renovadoDesdeId) {
        log.info("Iniciando creación de turno fijo. Email: {}, Cancha: {}, Día: {}, Horario: {}-{}, Período: {} a {}",
                email, request.canchaId(), request.diaSemana(), request.horaInicio(), request.horaFin(),
                request.fechaInicioPeriodo(), request.fechaFinPeriodo());

        if (request.fechaInicioPeriodo().isAfter(request.fechaFinPeriodo())) {
            throw new IllegalArgumentException("La fecha de inicio del período no puede ser posterior a la fecha de fin del período");
        }
        if (!request.horaInicio().isBefore(request.horaFin())) {
            throw new IllegalArgumentException("La hora de inicio debe ser anterior a la hora de fin");
        }
        validarPeriodoDentroDelAnio(request.fechaInicioPeriodo(), request.fechaFinPeriodo());

        Cancha cancha = reservaService.buscarCanchaPorId(request.canchaId());
        autorizacionEmpleadoService.validarPropietarioOAdmin(cancha.getEstablecimiento(), email);
        reservaService.validarDeporteSoportado(request.deporteSeleccionado(), cancha);

        Usuario jugador = null;
        if (request.jugadorId() != null) {
            jugador = usuarioRepository.findById(request.jugadorId())
                    .orElseThrow(() -> new EntityNotFoundException("Jugador no encontrado"));
            if (jugador.getRol() != Role.PLAYER) {
                throw new IllegalArgumentException("El jugadorId indicado no corresponde a un usuario con rol PLAYER");
            }
        } else if (request.nombreClienteManual() == null || request.nombreClienteManual().isBlank()) {
            throw new IllegalArgumentException("Debe indicar un jugador registrado (jugadorId) o el nombre del cliente (nombreClienteManual)");
        }

        List<LocalDate> fechasDelPeriodo = generarFechasDelPeriodo(
                request.fechaInicioPeriodo(), request.fechaFinPeriodo(), request.diaSemana());
        if (fechasDelPeriodo.isEmpty()) {
            throw new IllegalArgumentException("El período indicado no contiene ningún " + request.diaSemana());
        }

        // Se bloquea una sola vez para todo el turno fijo: la cancha (y su pool) no cambia
        // entre ocurrencias, solo la fecha/hora, así que un único lock por transacción alcanza.
        List<Cancha> todasLasCanchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(cancha.getEstablecimiento().getId());
        reservaService.bloquearCanchasRelacionadas(cancha, todasLasCanchas);

        // Se precarga una sola vez el rango completo del período (días no laborables,
        // bloqueos y reservas existentes) y se filtra en memoria por cada ocurrencia, en
        // vez de repetir 3 queries por fecha (mismo patrón que DisponibilidadService, que
        // enfrenta el mismo problema al generar la grilla completa de turnos libres).
        Long establecimientoId = cancha.getEstablecimiento().getId();
        LocalDate primeraFecha = fechasDelPeriodo.get(0);
        LocalDate ultimaFecha = fechasDelPeriodo.get(fechasDelPeriodo.size() - 1);
        LocalDateTime rangoInicio = primeraFecha.atStartOfDay();
        LocalDateTime rangoFin = ultimaFecha.plusDays(1).atTime(LocalTime.MAX);

        List<DiaNoLaborable> diasNoLaborables = diaNoLaborableRepository
                .findByEstablecimientoIdAndFechaBetween(establecimientoId, primeraFecha, ultimaFecha);
        List<BloqueoCancha> bloqueosEnRango = bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, rangoInicio, rangoFin);
        List<Reserva> reservasEnRango = reservaRepository.findSuperpuestas(establecimientoId, rangoInicio, rangoFin, LocalDateTime.now());

        // La regla se persiste ANTES que las ocurrencias, para tener el id que va en la FK
        // de cada Reserva.turnoFijo. Si alguna fecha del período no tiene disponibilidad, la
        // excepción de más abajo hace rollback de la transacción completa (regla incluida):
        // el alta sigue siendo todo-o-nada.
        TurnoFijo regla = turnoFijoRepository.save(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(request.deporteSeleccionado())
                .diaSemana(request.diaSemana())
                .horaInicio(request.horaInicio())
                .horaFin(request.horaFin())
                .fechaInicioPeriodo(request.fechaInicioPeriodo())
                .fechaFinPeriodo(request.fechaFinPeriodo())
                .jugador(jugador)
                .nombreClienteManual(jugador == null ? request.nombreClienteManual() : null)
                .telefonoClienteManual(jugador == null ? request.telefonoClienteManual() : null)
                .estado(EstadoTurnoFijo.ACTIVO)
                .renovadoDesdeId(renovadoDesdeId)
                .build());

        List<Reserva> reservasAGuardar = new ArrayList<>();
        for (LocalDate fecha : fechasDelPeriodo) {
            LocalDateTime inicio = fecha.atTime(request.horaInicio());
            LocalDateTime fin = fecha.atTime(request.horaFin());

            try {
                reservaService.validarFechas(inicio, fin);
                reservaService.validarGranularidadHoraria(inicio, cancha);
                long duracionMinutos = reservaService.validarDuracion(inicio, fin, cancha);
                reservaService.validarSinBloqueos(inicio, fin, cancha, bloqueosEnRango);
                reservaService.validarDiaNoLaborable(inicio, cancha.getEstablecimiento(), diasNoLaborables);
                reservaService.validarHorarioAtencion(inicio, fin, cancha.getEstablecimiento());

                List<Reserva> solapadas = reservasEnRango.stream()
                        .filter(r -> reservaService.seSuperponen(r.getFechaHoraInicio(), r.getFechaHoraFin(), inicio, fin))
                        .toList();
                reservaService.validarCanchaExactaLibre(cancha, solapadas);
                reservaService.validarPoolCanchas(cancha, solapadas, todasLasCanchas);

                BigDecimal precioCalculado = reservaService.calcularPrecio(cancha, inicio, duracionMinutos);

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
                        .turnoFijo(regla)
                        .build());
            } catch (IllegalArgumentException ex) {
                log.warn("Turno fijo rechazado en la fecha {}: {}", fecha, ex.getMessage());
                throw new IllegalArgumentException("No se pudo crear el turno fijo para el " + fecha + ": " + ex.getMessage());
            }
        }

        List<Reserva> reservasGuardadas = reservaRepository.saveAll(reservasAGuardar);
        log.info("Turno fijo creado con éxito. Regla: {}, {} reservas generadas para la cancha {}",
                regla.getId(), reservasGuardadas.size(), cancha.getNombre());
        // UN evento para todo el turno fijo, no uno por ocurrencia: el destinatario espera un
        // solo aviso con la lista de fechas, y un evento por ocurrencia encolaba una tarea
        // @Async por fecha contra un pool con cola de 50 (ver TurnoFijoCreadoEvent).
        eventPublisher.publishEvent(new TurnoFijoCreadoEvent(
                reservasGuardadas.stream().map(Reserva::getId).toList()));

        return turnoFijoMapper.mapToResponse(regla,
                reservasGuardadas.stream().map(reservaMapper::mapToResponse).toList());
    }

    /**
     * Listado de turnos fijos del establecimiento. Por defecto sólo los ACTIVOS: los
     * cancelados se piden explícitamente, para auditar.
     *
     * <p>Lectura, no escritura: un empleado que ya ve la agenda tiene que poder ver también
     * las series. No puede cancelarlas ni renovarlas — eso pasa por validarPropietarioOAdmin.
     *
     * <p>cantidadOcurrenciasActivas y proximaOcurrencia NO se calculan por fila: se resuelven
     * con una sola consulta agregada por los ids de la página entera (ver
     * ReservaRepository.agregadosPorTurnoFijo). Sin esto el listado dispara dos consultas
     * extra por fila, el N+1 clásico de un listado con agregados.
     */
    @Transactional(readOnly = true)
    public Page<TurnoFijoListadoResponse> listar(Long estId, EstadoTurnoFijo estado, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(estId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarLectura(establecimiento, email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        EstadoTurnoFijo estadoBuscado = estado != null ? estado : EstadoTurnoFijo.ACTIVO;
        Page<TurnoFijo> pagina = turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                estId, estadoBuscado, reservaService.capPageSize(pageable));

        List<Long> ids = pagina.getContent().stream().map(TurnoFijo::getId).toList();
        Map<Long, Object[]> agregados = ids.isEmpty() ? Map.of()
                : reservaRepository.agregadosPorTurnoFijo(ids, LocalDateTime.now()).stream()
                        .collect(Collectors.toMap(fila -> (Long) fila[0], fila -> fila));

        return pagina.map(turnoFijo -> turnoFijoMapper.mapToListado(turnoFijo, agregados.get(turnoFijo.getId())));
    }

    /**
     * Detalle de una serie con todas sus ocurrencias, para auditarla o revisarla antes de
     * cancelarla o renovarla.
     *
     * <p>Lectura, no escritura: mismo criterio de autorización que listar.
     */
    @Transactional(readOnly = true)
    public TurnoFijoResponse detalle(Long id, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarLectura(turnoFijo.getCancha().getEstablecimiento(), email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        List<ReservaResponse> ocurrencias = reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id)
                .stream().map(reservaMapper::mapToResponse).toList();
        return turnoFijoMapper.mapToResponse(turnoFijo, ocurrencias);
    }

    private TurnoFijo buscarTurnoFijo(Long id) {
        return turnoFijoRepository.findByIdConCanchaYEstablecimiento(id)
                .orElseThrow(() -> new EntityNotFoundException("Turno fijo no encontrado"));
    }

    /**
     * Da de baja la serie desde una fecha en adelante (por defecto, desde ahora).
     *
     * <p>El corte es fechaHoraInicio > max(ahora, desde): cancelar "desde hoy" a las 21 no
     * toca el turno de hoy a las 20, que ya se jugó y hay que finalizar o marcar ausente,
     * no cancelar.
     *
     * <p>canceladoDesde persiste el corte EFECTIVO (max(ahora, desde).toLocalDate()), no el
     * `desde` crudo del pedido: con una fecha pasada, el corte real ya queda en "ahora" (no
     * se toca nada que ya pasó), pero si canceladoDesde guardara la fecha pasada, el campo
     * mentiría — su semántica es "la serie dejó de generar compromiso a partir de acá", y
     * ese valor se expone tal cual en TurnoFijoResponse/TurnoFijoListadoResponse. Se clampea
     * en vez de rechazar: un dueño que manda una fecha pasada está diciendo "dala de baja
     * desde ya", y esa es la lectura honesta del pedido.
     *
     * <p>No toma el lock pesimista de la cancha: cancelar no crea solapamientos. El @Version
     * de cada Reserva alcanza.
     */
    public CancelacionTurnoFijoResponse cancelar(Long id, LocalDate desde, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(turnoFijo.getCancha().getEstablecimiento(), email);

        LocalDate desdeEfectiva = desde != null ? desde : LocalDate.now();
        LocalDateTime corte = maximo(LocalDateTime.now(), desdeEfectiva.atStartOfDay());
        LocalDate corteEfectivo = corte.toLocalDate();

        List<Reserva> canceladas = new ArrayList<>();
        List<CancelacionTurnoFijoResponse.OcurrenciaOmitida> omitidas = new ArrayList<>();

        for (Reserva ocurrencia : reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id)) {
            if (!ocurrencia.getFechaHoraInicio().isAfter(corte)) {
                continue;   // ya pasó o queda antes del corte pedido: no es parte de la baja
            }
            if (ESTADOS_INTOCABLES.contains(ocurrencia.getEstado())) {
                omitidas.add(new CancelacionTurnoFijoResponse.OcurrenciaOmitida(
                        ocurrencia.getFechaHoraInicio(), ocurrencia.getEstado().name()));
                continue;
            }
            ocurrencia.setEstado(EstadoReserva.CANCELADA);
            canceladas.add(ocurrencia);
        }

        reservaRepository.saveAll(canceladas);

        turnoFijo.setEstado(EstadoTurnoFijo.CANCELADO);
        turnoFijo.setCanceladoDesde(corteEfectivo);
        turnoFijoRepository.save(turnoFijo);

        log.info("Turno fijo {} cancelado desde {}. {} ocurrencias dadas de baja, {} omitidas",
                id, corteEfectivo, canceladas.size(), omitidas.size());

        if (!canceladas.isEmpty()) {
            eventPublisher.publishEvent(new TurnoFijoCanceladoEvent(
                    id, canceladas.stream().map(Reserva::getId).toList()));
        }

        return new CancelacionTurnoFijoResponse(canceladas.size(), omitidas);
    }

    /**
     * Corrige a quién figura la serie. Sólo en series de mostrador: si la serie está atada a
     * un jugador registrado, el nombre sale de su cuenta y no es un campo editable acá.
     *
     * <p>Propaga sólo a las ocurrencias futuras y vivas ({@code fechaHoraInicio} posterior a
     * ahora, en estado CONFIRMADA o PENDIENTE_SENA). Las pasadas son registro de lo que
     * ocurrió y no se reescriben.
     */
    public TurnoFijoResponse editarCliente(Long id, EditarClienteTurnoFijoRequest request, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(turnoFijo.getCancha().getEstablecimiento(), email);

        if (turnoFijo.getJugador() != null) {
            throw new IllegalArgumentException(
                    "Este turno fijo está a nombre de un jugador registrado: el nombre sale de su cuenta.");
        }

        turnoFijo.setNombreClienteManual(request.nombre());
        turnoFijo.setTelefonoClienteManual(request.telefono());
        turnoFijoRepository.save(turnoFijo);

        List<Reserva> ocurrencias = reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id);

        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> aActualizar = ocurrencias.stream()
                .filter(r -> r.getFechaHoraInicio().isAfter(ahora))
                .filter(r -> r.getEstado() == EstadoReserva.CONFIRMADA
                          || r.getEstado() == EstadoReserva.PENDIENTE_SENA)
                .toList();
        // For explícito y no .peek(): peek() no garantiza ejecutar su acción para todos los
        // elementos en toda pipeline (por ejemplo si se agregara un short-circuit más
        // adelante), y usarlo para un efecto de lado que sí necesitamos que corra siempre
        // es un antipatrón.
        for (Reserva ocurrencia : aActualizar) {
            ocurrencia.setNombreClienteManual(request.nombre());
            ocurrencia.setTelefonoClienteManual(request.telefono());
        }
        reservaRepository.saveAll(aActualizar);

        // TurnoFijoResponse documenta "ocurrencias" como poblada salvo en el listado: acá no
        // es el listado, así que van las ocurrencias ya cargadas arriba (mismo objeto, sin
        // pegarle una segunda consulta a la base).
        return turnoFijoMapper.mapToResponse(turnoFijo,
                ocurrencias.stream().map(reservaMapper::mapToResponse).toList());
    }

    private LocalDateTime maximo(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    /**
     * Un turno fijo se carga por año calendario: la fecha de fin no puede pasar del 31/12
     * del año en que arranca. A diferencia de las reservas puntuales, acá NO aplica el
     * límite de anticipación de ReservaService — un turno fijo existe justamente para el
     * largo plazo — pero sin ningún tope el período tampoco tenía techo: "todos los lunes
     * hasta 2040" son ~520 reservas creadas en UNA transacción, con el lock pesimista de la
     * cancha tomado de punta a punta (ver reservaService.bloquearCanchasRelacionadas), más
     * el aviso al jugador de un compromiso a 15 años. El año calendario es el corte que el
     * negocio usa para renovar: al llegar diciembre se carga el del año siguiente.
     */
    private void validarPeriodoDentroDelAnio(LocalDate fechaInicioPeriodo, LocalDate fechaFinPeriodo) {
        LocalDate ultimoDiaDelAnio = LocalDate.of(fechaInicioPeriodo.getYear(), 12, 31);
        if (fechaFinPeriodo.isAfter(ultimoDiaDelAnio)) {
            throw new IllegalArgumentException(
                    "Un turno fijo se carga hasta el fin del año en el que empieza: la fecha de fin no puede "
                            + "pasar del " + ultimoDiaDelAnio.format(FORMATO_FECHA)
                            + ". Para el año siguiente, cargá un turno fijo nuevo.");
        }
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
}
