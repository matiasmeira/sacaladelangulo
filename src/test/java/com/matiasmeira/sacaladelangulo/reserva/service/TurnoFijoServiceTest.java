package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests de TurnoFijoService.crear, movidos de ReservaServiceTest (crearReservaSemanal)
 * cuando la creación del turno fijo se independizó en su propio servicio (ver
 * TurnoFijoService).
 *
 * <p>ReservaService se instancia REAL (no mockeada) con los mismos mocks de repositorios
 * que recibe TurnoFijoService: las validaciones compartidas (horario de atención,
 * bloqueos, pool de canchas, etc.) son package-private en ReservaService y deben
 * ejecutarse de verdad acá, para que este test siga cubriendo el mismo comportamiento de
 * negocio que cubría antes en ReservaServiceTest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TurnoFijoService - Tests de creación de turnos fijos")
class TurnoFijoServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private BloqueoJugadorRepository bloqueoJugadorRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ReservaMapper reservaMapper;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TurnoCajaService turnoCajaService;

    @Mock
    private TurnoFijoRepository turnoFijoRepository;

    /**
     * Instancia REAL (no @InjectMocks): TurnoFijoService la usa por inyección para no
     * duplicar los validadores compartidos (ver comentario de clase).
     */
    private ReservaService reservaService;

    /** Instancia REAL: es un mapper trivial, mockearla obligaría a re-stubear cada campo. */
    private TurnoFijoMapper turnoFijoMapper;

    private TurnoFijoService turnoFijoService;

    private static final Long EST_ID = 10L;
    private static final String EMAIL_DUENO = "dueno@test.com";
    private static final String EMAIL_INTRUSO = "intruso@test.com";
    private static final String EMAIL_EMPLEADO = "empleado@test.com";
    private static final Long TURNO_FIJO_ID = 300L;

    private Usuario jugador;
    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;
    private TurnoFijo turnoFijoActivo;
    private TurnoFijo otroTurnoFijoActivo;

    @BeforeEach
    void setUp() {
        jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .password("password")
                .nombre("Juan")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .password("password")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        establecimiento.setHorariosAtencion(List.of(
                HorarioAtencion.builder()
                        .diaSemana(DayOfWeek.TUESDAY)
                        .horaApertura(LocalTime.of(10, 0))
                        .horaCierre(LocalTime.of(22, 0))
                        .establecimiento(establecimiento)
                        .build()
        ));

        cancha = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        reservaService = new ReservaService(
                reservaRepository, canchaRepository, bloqueoCanchaRepository, bloqueoJugadorRepository,
                diaNoLaborableRepository, establecimientoRepository, usuarioRepository, reservaMapper,
                autorizacionEmpleadoService, registroAuditoriaService, eventPublisher, turnoCajaService);
        turnoFijoMapper = new TurnoFijoMapper(reservaMapper);
        turnoFijoService = new TurnoFijoService(
                turnoFijoRepository, reservaRepository, canchaRepository, bloqueoCanchaRepository,
                diaNoLaborableRepository, establecimientoRepository, usuarioRepository, autorizacionEmpleadoService,
                reservaMapper, turnoFijoMapper, eventPublisher, reservaService);

        turnoFijoActivo = TurnoFijo.builder()
                .id(TURNO_FIJO_ID)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 8))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Grupo del Colo")
                .telefonoClienteManual("11 5555-4444")
                .build();

        otroTurnoFijoActivo = TurnoFijo.builder()
                .id(301L)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DayOfWeek.WEDNESDAY)
                .horaInicio(LocalTime.of(19, 0))
                .horaFin(LocalTime.of(20, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 9))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Otro Grupo")
                .build();

        // Default: el establecimiento del dueño se resuelve para listar/detalle. Cada test
        // puede pisarlo si necesita otro escenario.
        lenient().when(establecimientoRepository.findById(EST_ID)).thenReturn(Optional.of(establecimiento));

        lenient().when(reservaMapper.mapToResponse(any(Reserva.class))).thenAnswer(invocation -> {
            Reserva reserva = invocation.getArgument(0);
            return new ReservaResponse(
                    reserva.getId(),
                    reserva.getJugador() != null ? reserva.getJugador().getId() : null,
                    reserva.getJugador() != null ? reserva.getJugador().getNombre() : null,
                    reserva.getCancha().getId(),
                    reserva.getCancha().getNombre(),
                    reserva.getFechaHoraInicio(),
                    reserva.getFechaHoraFin(),
                    reserva.getEstado().name(),
                    reserva.getPrecioTotal(),
                    reserva.getSenaPagada(),
                    reserva.getNombreClienteManual(),
                    reserva.getTelefonoClienteManual(),
                    reserva.getDeporteSeleccionado(),
                    reserva.getExpiraEn(),
                    reserva.getMetodoPago() != null ? reserva.getMetodoPago().name() : null,
                    reserva.getTurnoFijo() != null ? reserva.getTurnoFijo().getId() : null
            );
        });

        // Default: la regla se guarda "tal cual" (mismo patrón que reservaRepository.saveAll
        // en los tests de abajo), para poder inspeccionar sus campos después con el captor.
        lenient().when(turnoFijoRepository.save(any(TurnoFijo.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Default: ningún jugador está bloqueado. No debería ni consultarse desde el turno
        // fijo (ver crear_TurnoFijo_Exito_GeneraUnaReservaConfirmadaPorFecha), pero queda
        // lenient por si algún test nuevo lo necesita.
        lenient().when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(any(), any())).thenReturn(false);

        // Default para los tests de cancelar(): la regla se resuelve con su cancha y
        // establecimiento (mismo camino que buscarTurnoFijo). Cada test puede pisarlo.
        lenient().when(turnoFijoRepository.findByIdConCanchaYEstablecimiento(TURNO_FIJO_ID))
                .thenReturn(Optional.of(turnoFijoActivo));
    }

    /**
     * Una ocurrencia de turnoFijoActivo en la fecha/estado pedidos, para los tests de
     * cancelar(). Comparte cancha con el resto del fixture; lo único que varía por test es
     * la fecha (pasada/futura respecto de "ahora") y el estado.
     */
    private Reserva ocurrencia(LocalDateTime inicio, EstadoReserva estado) {
        return Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .turnoFijo(turnoFijoActivo)
                .build();
    }

    @Test
    @DisplayName("crear_TurnoFijo_PersisteLaReglaYLinkeaTodasLasOcurrencias")
    void crear_TurnoFijo_PersisteLaReglaYLinkeaTodasLasOcurrencias() {
        // Arrange: martes 08, 15, 22 y 29 de enero de 2030 (4 ocurrencias). Fechas en 2030,
        // no cercanas a "hoy", para que el test no dependa de cuándo se ejecuta la suite
        // (ver auto-revisión: las fechas originales de este test, heredadas del brief, ya
        // habían quedado en el pasado al momento de implementar esta tarea).
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(),
                LocalDate.of(2030, 1, 8),   // martes
                LocalDate.of(2030, 1, 29),
                DayOfWeek.TUESDAY,
                LocalTime.of(20, 0),
                LocalTime.of(21, 0),
                Deporte.FUTBOL_5,
                null,
                "Grupo del Colo",
                "11 5555-4444");

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TurnoFijoResponse respuesta = turnoFijoService.crear(request, dueno.getEmail());

        // Assert
        ArgumentCaptor<TurnoFijo> reglaCaptor = ArgumentCaptor.forClass(TurnoFijo.class);
        verify(turnoFijoRepository).save(reglaCaptor.capture());
        TurnoFijo regla = reglaCaptor.getValue();
        assertThat(regla.getDiaSemana()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(regla.getEstado()).isEqualTo(EstadoTurnoFijo.ACTIVO);
        assertThat(regla.getCanceladoDesde()).isNull();
        assertThat(regla.getHoraInicio()).isEqualTo(LocalTime.of(20, 0));
        assertThat(regla.getHoraFin()).isEqualTo(LocalTime.of(21, 0));
        assertThat(regla.getFechaInicioPeriodo()).isEqualTo(LocalDate.of(2030, 1, 8));
        assertThat(regla.getFechaFinPeriodo()).isEqualTo(LocalDate.of(2030, 1, 29));
        assertThat(regla.getCancha()).isSameAs(cancha);
        assertThat(regla.getJugador()).isNull();
        assertThat(regla.getNombreClienteManual()).isEqualTo("Grupo del Colo");
        assertThat(regla.getTelefonoClienteManual()).isEqualTo("11 5555-4444");

        ArgumentCaptor<List<Reserva>> reservasCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservaRepository).saveAll(reservasCaptor.capture());
        List<Reserva> ocurrencias = reservasCaptor.getValue();
        assertThat(ocurrencias).hasSize(4);
        assertThat(ocurrencias).allSatisfy(r ->
                assertThat(r.getTurnoFijo()).isSameAs(regla));

        assertThat(respuesta.ocurrencias()).hasSize(4);
        assertThat(respuesta.canchaId()).isEqualTo(cancha.getId());
        assertThat(respuesta.canchaNombre()).isEqualTo(cancha.getNombre());
        assertThat(respuesta.estado()).isEqualTo("ACTIVO");
        assertThat(respuesta.diaSemana()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(respuesta.horaInicio()).isEqualTo(LocalTime.of(20, 0));
        assertThat(respuesta.nombreClienteManual()).isEqualTo("Grupo del Colo");
        assertThat(respuesta.telefonoClienteManual()).isEqualTo("11 5555-4444");
    }

    @Test
    @DisplayName("crear_Exito_GeneraUnaReservaConfirmadaPorFecha")
    void crear_Exito_GeneraUnaReservaConfirmadaPorFecha() {
        // Arrange: martes 08, 15 y 22 de enero de 2030 (3 ocurrencias)
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL_5, null, "Cliente Fijo", "1122334455");

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        TurnoFijoResponse respuesta = assertDoesNotThrow(
                () -> turnoFijoService.crear(request, dueno.getEmail()));

        // Assert
        assert respuesta.ocurrencias().size() == 3;
        assert respuesta.ocurrencias().stream().allMatch(r -> r.estado().equals("CONFIRMADA"));
        assert respuesta.ocurrencias().stream().allMatch(r -> r.jugadorId() == null);
        assert respuesta.ocurrencias().stream().allMatch(r -> r.nombreClienteManual().equals("Cliente Fijo"));
        verify(reservaRepository).saveAll(argThat(list -> ((List<?>) list).size() == 3));
        // El bloqueo de jugadores solo aplica al autoservicio (crearReserva): el dueño puede
        // cargarle igual un turno fijo semanal a un jugador que él mismo haya bloqueado.
        verify(bloqueoJugadorRepository, never()).existsByEstablecimientoIdAndJugadorId(any(), any());
    }

    @Test
    @DisplayName("crear_Exito_PublicaUnSoloEventoConsolidadoConTodosLosIds")
    void crear_Exito_PublicaUnSoloEventoConsolidadoConTodosLosIds() {
        // Arrange: martes 08, 15 y 22 de enero de 2030 (3 ocurrencias)
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 1, 8), LocalDate.of(2030, 1, 22), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.FUTBOL_5, null, "Cliente Fijo", "1122334455");

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> {
            List<Reserva> reservas = invocation.getArgument(0);
            long id = 500L;
            for (Reserva reserva : reservas) {
                reserva.setId(id++);
            }
            return reservas;
        });

        // Act
        turnoFijoService.crear(request, dueno.getEmail());

        // Assert: un turno fijo es UN aviso, no uno por ocurrencia. Con un evento por
        // ocurrencia, un turno fijo anual encola 52 tareas @Async contra un pool con cola
        // de 50 y manda 104 emails (ver B-07 en la auditoría).
        verify(eventPublisher).publishEvent(new TurnoFijoCreadoEvent(List.of(500L, 501L, 502L)));
        verify(eventPublisher, never()).publishEvent(any(ReservaConfirmadaEvent.class));
    }

    @Test
    @DisplayName("crear_Fallo_PeriodoQuePasaDelFinDeAnio")
    void crear_Fallo_PeriodoQuePasaDelFinDeAnio() {
        // Un turno fijo se carga por año calendario: sin tope, "todos los lunes hasta 2040"
        // arma ~700 reservas en una sola transacción, con la cancha bloqueada mientras corre.
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 11, 5), LocalDate.of(2031, 2, 4), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.FUTBOL_5, null, "Cliente Fijo", "1122334455");

        IllegalArgumentException excepcion = assertThrows(IllegalArgumentException.class,
                () -> turnoFijoService.crear(request, dueno.getEmail()));

        assertTrue(excepcion.getMessage().contains("31/12/2030"),
                "el mensaje tiene que decir hasta qué fecha se puede cargar: " + excepcion.getMessage());
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crear_Exito_PeriodoQueTerminaJustoElUltimoDiaDelAnio")
    void crear_Exito_PeriodoQueTerminaJustoElUltimoDiaDelAnio() {
        // Borde inclusivo: el 31/12 tiene que entrar.
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 12, 3), LocalDate.of(2030, 12, 31), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.FUTBOL_5, null, "Cliente Fijo", "1122334455");

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TurnoFijoResponse respuesta = assertDoesNotThrow(
                () -> turnoFijoService.crear(request, dueno.getEmail()));

        // Martes 03, 10, 17, 24 y 31 de diciembre de 2030.
        assertEquals(5, respuesta.ocurrencias().size());
    }

    @Test
    @DisplayName("crear_Fallo_JugadorIdNoEsRolPlayer")
    void crear_Fallo_JugadorIdNoEsRolPlayer() {
        // Arrange
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL_5, dueno.getId(), null, null);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(usuarioRepository.findById(dueno.getId())).thenReturn(Optional.of(dueno));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> turnoFijoService.crear(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("PLAYER");
        verify(reservaRepository, never()).saveAll(any());
        verify(turnoFijoRepository, never()).save(any());
    }

    @Test
    @DisplayName("crear_Fallo_TodoONada_UnaFechaBloqueada")
    void crear_Fallo_TodoONada_UnaFechaBloqueada() {
        // Arrange
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL_5, null, "Cliente Fijo", null);

        LocalDateTime inicioBloqueado = LocalDate.of(2030, 1, 22).atTime(horaInicio);
        LocalDateTime finBloqueado = LocalDate.of(2030, 1, 22).atTime(horaFin);

        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .id(1L)
                .cancha(cancha)
                .fechaInicio(inicioBloqueado)
                .fechaFin(finBloqueado)
                .motivo("Mantenimiento")
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of(bloqueo));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> turnoFijoService.crear(request, dueno.getEmail())
        );

        // Assert: todo-o-nada, no debe guardarse ninguna reserva aunque las 2 primeras fechas eran válidas
        assert exception.getMessage().contains("2030-01-22");
        assert exception.getMessage().contains("bloqueada");
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crear_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crear_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 1, 8), LocalDate.of(2030, 1, 22), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.FUTBOL_5, null, "Cliente Fijo", null);

        Usuario otroDueno = Usuario.builder()
                .id(4L)
                .email("otro-dueno-semanal@test.com")
                .password("password")
                .nombre("Otro Dueño")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> turnoFijoService.crear(request, otroDueno.getEmail())
        );
    }

    @Test
    @DisplayName("crear_Fallo_TodoONada_DiaNoLaborableEnUnaFecha")
    void crear_Fallo_TodoONada_DiaNoLaborableEnUnaFecha() {
        // Arrange: martes 08, 15 y 22 de enero de 2030; el 15 es feriado
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL_5, null, "Cliente Fijo", null);

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.of(2030, 1, 15))
                .motivo("Feriado nacional")
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of(diaNoLaborable));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));

        // Act & Assert: todo-o-nada, no debe guardarse nada aunque el 08 era válido
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> turnoFijoService.crear(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("2030-01-15");
        assert exception.getMessage().contains("Feriado nacional");
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crear_Fallo_DeporteNoSoportadoPorLaCancha")
    void crear_Fallo_DeporteNoSoportadoPorLaCancha() {
        // Arrange
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 1, 8), LocalDate.of(2030, 1, 22), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.TENIS, null, "Cliente Fijo", null);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> turnoFijoService.crear(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("TENIS");
    }

    @Test
    @DisplayName("listar_SinEstado_TraeSoloLosActivos")
    void listar_SinEstado_TraeSoloLosActivos() {
        // arrange: establecimiento del dueño, un turno fijo ACTIVO y uno CANCELADO
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                eq(EST_ID), eq(EstadoTurnoFijo.ACTIVO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(turnoFijoActivo)));

        var pagina = turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        assertThat(pagina.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("listar_ResuelveLosAgregadosEnUnaSolaConsulta")
    void listar_ResuelveLosAgregadosEnUnaSolaConsulta() {
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(anyLong(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(turnoFijoActivo, otroTurnoFijoActivo)));

        turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        // Una sola llamada agregada para toda la página, no una por fila.
        verify(reservaRepository, times(1)).agregadosPorTurnoFijo(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("listar_ConEstadoExplicito_NoAplicaElActivoPorDefecto")
    void listar_ConEstadoExplicito_NoAplicaElActivoPorDefecto() {
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                eq(EST_ID), eq(EstadoTurnoFijo.CANCELADO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        turnoFijoService.listar(EST_ID, EstadoTurnoFijo.CANCELADO, PageRequest.of(0, 20), EMAIL_DUENO);

        verify(turnoFijoRepository).findByCancha_Establecimiento_IdAndEstado(
                eq(EST_ID), eq(EstadoTurnoFijo.CANCELADO), any(Pageable.class));
        verify(turnoFijoRepository, never()).findByCancha_Establecimiento_IdAndEstado(
                eq(EST_ID), eq(EstadoTurnoFijo.ACTIVO), any(Pageable.class));
    }

    @Test
    @DisplayName("listar_PaginaVacia_NoConsultaAgregados")
    void listar_PaginaVacia_NoConsultaAgregados() {
        // Sin filas que agregar, no vale la pena ni disparar la consulta.
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(anyLong(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        Page<TurnoFijoListadoResponse> pagina = turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        assertThat(pagina.getContent()).isEmpty();
        verify(reservaRepository, never()).agregadosPorTurnoFijo(any(), any());
    }

    @Test
    @DisplayName("listar_MapeaLosAgregadosPorSerieYCeroCuandoNoHayOcurrenciasFuturas")
    void listar_MapeaLosAgregadosPorSerieYCeroCuandoNoHayOcurrenciasFuturas() {
        LocalDateTime proxima = LocalDateTime.of(2030, 1, 15, 20, 0);
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(anyLong(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(turnoFijoActivo, otroTurnoFijoActivo)));
        // Solo turnoFijoActivo tiene fila en el agregado: otroTurnoFijoActivo no tiene
        // ninguna ocurrencia futura viva y no aparece en el resultado de la consulta.
        when(reservaRepository.agregadosPorTurnoFijo(anyList(), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(new Object[]{TURNO_FIJO_ID, 3L, proxima}));

        Page<TurnoFijoListadoResponse> pagina = turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        TurnoFijoListadoResponse conOcurrencias = pagina.getContent().stream()
                .filter(r -> r.id().equals(TURNO_FIJO_ID)).findFirst().orElseThrow();
        assertThat(conOcurrencias.ocurrenciasActivas()).isEqualTo(3L);
        assertThat(conOcurrencias.proximaOcurrencia()).isEqualTo(proxima);

        TurnoFijoListadoResponse sinOcurrencias = pagina.getContent().stream()
                .filter(r -> r.id().equals(301L)).findFirst().orElseThrow();
        assertThat(sinOcurrencias.ocurrenciasActivas()).isEqualTo(0L);
        assertThat(sinOcurrencias.proximaOcurrencia()).isNull();
    }

    @Test
    @DisplayName("listar_TamanioDePaginaSuperaElMaximo_LoCapeaA100")
    void listar_TamanioDePaginaSuperaElMaximo_LoCapeaA100() {
        // Mismo cap de ReservaService: no puede quedar sin techo el día que alguien
        // cambie el de ReservaService y se olvide de este.
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(anyLong(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(2), 0));

        Page<TurnoFijoListadoResponse> pagina = turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 500), EMAIL_DUENO);

        assertThat(pagina.getPageable().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("listar_DeOtroEstablecimiento_LanzaAccessDenied")
    void listar_DeOtroEstablecimiento_LanzaAccessDenied() {
        // eq(establecimiento) y no any(): si el código autorizara contra el establecimiento
        // equivocado, el stub no matchearía, no se lanzaría la excepción, y
        // assertThatThrownBy fallaría sola (además del verify de abajo, que ancla los tres
        // argumentos explícitamente).
        doThrow(new AccessDeniedException("No autorizado"))
                .when(autorizacionEmpleadoService).validarLectura(eq(establecimiento), eq(EMAIL_INTRUSO), any());

        assertThatThrownBy(() -> turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_INTRUSO))
                .isInstanceOf(AccessDeniedException.class);
        verify(turnoFijoRepository, never()).findByCancha_Establecimiento_IdAndEstado(any(), any(), any());
        verify(autorizacionEmpleadoService).validarLectura(
                eq(establecimiento), eq(EMAIL_INTRUSO),
                eq(AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA));
    }

    @Test
    @DisplayName("detalle_DeOtroEstablecimiento_LanzaAccessDenied")
    void detalle_DeOtroEstablecimiento_LanzaAccessDenied() {
        when(turnoFijoRepository.findByIdConCanchaYEstablecimiento(TURNO_FIJO_ID))
                .thenReturn(Optional.of(turnoFijoActivo));
        // El establecimiento de la CANCHA DE LA SERIE, no cualquier otro: es justo la
        // distinción que este test tiene que poder detectar (ver comentario de arriba).
        Establecimiento establecimientoDeLaSerie = turnoFijoActivo.getCancha().getEstablecimiento();
        doThrow(new AccessDeniedException("No autorizado"))
                .when(autorizacionEmpleadoService).validarLectura(eq(establecimientoDeLaSerie), eq(EMAIL_INTRUSO), any());

        assertThatThrownBy(() -> turnoFijoService.detalle(TURNO_FIJO_ID, EMAIL_INTRUSO))
                .isInstanceOf(AccessDeniedException.class);
        verify(autorizacionEmpleadoService).validarLectura(
                eq(establecimientoDeLaSerie), eq(EMAIL_INTRUSO),
                eq(AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA));
    }

    @Test
    @DisplayName("detalle_Exito_DevuelveLaReglaConSusOcurrencias")
    void detalle_Exito_DevuelveLaReglaConSusOcurrencias() {
        when(turnoFijoRepository.findByIdConCanchaYEstablecimiento(TURNO_FIJO_ID))
                .thenReturn(Optional.of(turnoFijoActivo));
        Reserva ocurrencia = Reserva.builder()
                .id(900L)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 8, 20, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 8, 21, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .turnoFijo(turnoFijoActivo)
                .build();
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(ocurrencia));

        TurnoFijoResponse respuesta = turnoFijoService.detalle(TURNO_FIJO_ID, EMAIL_DUENO);

        assertThat(respuesta.id()).isEqualTo(TURNO_FIJO_ID);
        assertThat(respuesta.ocurrencias()).hasSize(1);
        assertThat(respuesta.ocurrencias().get(0).id()).isEqualTo(900L);
    }

    @Test
    @DisplayName("detalle_TurnoFijoInexistente_LanzaEntityNotFound")
    void detalle_TurnoFijoInexistente_LanzaEntityNotFound() {
        when(turnoFijoRepository.findByIdConCanchaYEstablecimiento(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> turnoFijoService.detalle(999L, EMAIL_DUENO));
    }

    @Test
    @DisplayName("cancelar_CancelaLasFuturasYDejaIntactasLasPasadas")
    void cancelar_CancelaLasFuturasYDejaIntactasLasPasadas() {
        Reserva pasada = ocurrencia(LocalDateTime.now().minusDays(7), EstadoReserva.CONFIRMADA);
        Reserva futura = ocurrencia(LocalDateTime.now().plusDays(7), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(pasada, futura));

        var resumen = turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        assertThat(resumen.canceladas()).isEqualTo(1);
        assertThat(pasada.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(futura.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
        // La postcondición central de "cancelar como UNIDAD": la REGLA también tiene que
        // quedar dada de baja, no sólo sus ocurrencias. Es además el par exacto que exige el
        // CHECK chk_turnos_fijos_cancelacion de V24 (estado=CANCELADO <=> canceladoDesde no
        // nulo): con Flyway apagado en los tests, nada más lo detecta.
        assertThat(turnoFijoActivo.getEstado()).isEqualTo(EstadoTurnoFijo.CANCELADO);
        assertThat(turnoFijoActivo.getCanceladoDesde()).isEqualTo(LocalDate.now());
    }

    @Test
    @DisplayName("cancelar_ConFechaPasada_ClampeaCanceladoDesdeAHoyEnVezDeMentir")
    void cancelar_ConFechaPasada_ClampeaCanceladoDesdeAHoyEnVezDeMentir() {
        // Una fecha pasada no cambia el corte real (sigue siendo "ahora": nada que ya pasó
        // se toca), pero si canceladoDesde guardara la fecha pasada tal cual, el campo
        // mentiría: diría que la serie no generaba compromiso desde hace 5 años, mientras
        // esas ocurrencias siguen CONFIRMADA/FINALIZADA con plata cobrada.
        Reserva futura = ocurrencia(LocalDateTime.now().plusDays(5), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(futura));

        turnoFijoService.cancelar(TURNO_FIJO_ID, LocalDate.now().minusYears(5), EMAIL_DUENO);

        assertThat(turnoFijoActivo.getCanceladoDesde()).isEqualTo(LocalDate.now());
        assertThat(futura.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("cancelar_OmiteFinalizadasYAusentesYLasReporta")
    void cancelar_OmiteFinalizadasYAusentesYLasReporta() {
        Reserva finalizada = ocurrencia(LocalDateTime.now().plusDays(1), EstadoReserva.FINALIZADA);
        Reserva ausente = ocurrencia(LocalDateTime.now().plusDays(2), EstadoReserva.AUSENTE);
        Reserva viva = ocurrencia(LocalDateTime.now().plusDays(3), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(finalizada, ausente, viva));

        var resumen = turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        assertThat(resumen.canceladas()).isEqualTo(1);
        assertThat(resumen.omitidas()).hasSize(2);
        assertThat(finalizada.getEstado()).isEqualTo(EstadoReserva.FINALIZADA);
        assertThat(ausente.getEstado()).isEqualTo(EstadoReserva.AUSENTE);
    }

    @Test
    @DisplayName("cancelar_PublicaUnSoloEventoYNoUnoPorOcurrencia")
    void cancelar_PublicaUnSoloEventoYNoUnoPorOcurrencia() {
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(
                        ocurrencia(LocalDateTime.now().plusDays(1), EstadoReserva.CONFIRMADA),
                        ocurrencia(LocalDateTime.now().plusDays(8), EstadoReserva.CONFIRMADA),
                        ocurrencia(LocalDateTime.now().plusDays(15), EstadoReserva.CONFIRMADA)));

        turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        verify(eventPublisher, times(1)).publishEvent(any(TurnoFijoCanceladoEvent.class));
        verify(eventPublisher, never()).publishEvent(any(ReservaCanceladaEvent.class));
    }

    @Test
    @DisplayName("cancelar_DesdeUnaFechaFutura_NoTocaLasAnteriores")
    void cancelar_DesdeUnaFechaFutura_NoTocaLasAnteriores() {
        Reserva antes = ocurrencia(LocalDateTime.now().plusDays(3), EstadoReserva.CONFIRMADA);
        Reserva despues = ocurrencia(LocalDateTime.now().plusDays(17), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(antes, despues));

        turnoFijoService.cancelar(TURNO_FIJO_ID, LocalDate.now().plusDays(10), EMAIL_DUENO);

        assertThat(antes.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(despues.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("cancelar_ComoEmpleado_LanzaAccessDenied")
    void cancelar_ComoEmpleado_LanzaAccessDenied() {
        doThrow(new AccessDeniedException("No autorizado"))
                .when(autorizacionEmpleadoService).validarPropietarioOAdmin(any(), eq(EMAIL_EMPLEADO));

        assertThatThrownBy(() -> turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_EMPLEADO))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("renovar_CreaLaSerieDelAnioSiguienteConRenovadoDesde")
    void renovar_CreaLaSerieDelAnioSiguienteConRenovadoDesde() {
        turnoFijoActivo.setFechaFinPeriodo(LocalDate.of(2026, 12, 31));
        when(turnoFijoRepository.existsByRenovadoDesdeId(TURNO_FIJO_ID)).thenReturn(false);
        // Mismo camino que crear(): renovar arma un ReservaSemanalRequest y pasa por
        // crearInterno, así que necesita el mismo arreglo que crear_TurnoFijo_Exito.
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        turnoFijoService.renovar(TURNO_FIJO_ID, EMAIL_DUENO);

        ArgumentCaptor<TurnoFijo> captor = ArgumentCaptor.forClass(TurnoFijo.class);
        verify(turnoFijoRepository, atLeastOnce()).save(captor.capture());
        TurnoFijo nueva = captor.getValue();
        assertThat(nueva.getRenovadoDesdeId()).isEqualTo(TURNO_FIJO_ID);
        assertThat(nueva.getFechaFinPeriodo()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    @DisplayName("renovar_YaRenovada_LanzaIllegalArgumentConMensajePropio")
    void renovar_YaRenovada_LanzaIllegalArgumentConMensajePropio() {
        when(turnoFijoRepository.existsByRenovadoDesdeId(TURNO_FIJO_ID)).thenReturn(true);

        assertThatThrownBy(() -> turnoFijoService.renovar(TURNO_FIJO_ID, EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya fue renovado");
    }
}
