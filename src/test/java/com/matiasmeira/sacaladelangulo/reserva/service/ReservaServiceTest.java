package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.JugadorBloqueadoException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservaService - Tests de solapamiento de reservas")
class ReservaServiceTest {

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

    @InjectMocks
    private ReservaService reservaService;

    private Usuario jugador;
    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;

    /**
     * A diferencia del resto de la suite (anclada a 2030 a propósito, para no depender de
     * cuándo se ejecuten los tests), los tests de crearReserva/crearReservaManual necesitan
     * una fecha real dentro de la ventana de anticipación de 31 días (ver M14 en la
     * auditoría), así que se calculan relativos a "hoy". El establecimiento de prueba solo
     * tiene horario de atención los martes.
     */
    private static final LocalDate FECHA_BASE = proximoMartes();

    private static LocalDate proximoMartes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.TUESDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

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
                        .diaSemana(java.time.DayOfWeek.TUESDAY)
                        .horaApertura(LocalTime.of(10, 0))
                        .horaCierre(LocalTime.of(22, 0))
                        .establecimiento(establecimiento)
                        .build()
        ));

        cancha = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

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
                    reserva.getMetodoPago() != null ? reserva.getMetodoPago().name() : null
            );
        });

        // Default: cualquier acción con permiso (crearReservaManual/finalizarReserva) pasa
        // la autorización como si la hiciera el dueño real. Los tests que necesiten otro
        // comportamiento (acceso denegado, empleado con/sin permiso) lo overridean.
        lenient().when(autorizacionEmpleadoService.validarAccion(any(), any(), any())).thenReturn(dueno);

        // Default: ningún jugador está bloqueado. Los tests de bloqueo lo overridean.
        lenient().when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(any(), any())).thenReturn(false);
    }

    @Test
    @DisplayName("crearReserva_Exito_SinSolapamiento")
    void crearReserva_Exito_SinSolapamiento() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        Reserva reservaGuardada = Reserva.builder()
                .id(1L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = assertDoesNotThrow(() -> reservaService.crearReserva(request, jugador.getEmail()));

        // Assert
        assert response != null;
        verify(reservaRepository).save(any(Reserva.class));
    }

    @Test
    @DisplayName("crearReserva_Fallo_JugadorBloqueado")
    void crearReserva_Fallo_JugadorBloqueado() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimiento.getId(), jugador.getId()))
                .thenReturn(true);

        // Act & Assert
        JugadorBloqueadoException exception = assertThrows(
                JugadorBloqueadoException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );
        assert exception.getMessage().contains("permitido");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearReserva_Fallo_DemasiadaAnticipacion")
    void crearReserva_Fallo_DemasiadaAnticipacion() {
        // Arrange: más de 31 días de anticipación (mismo tope que DisponibilidadService)
        LocalDateTime fechaInicio = FECHA_BASE.plusDays(40).atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.plusDays(40).atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );
        assert exception.getMessage().contains("anticipación");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearReserva_Fallo_CanchaExactaSolapada")
    void crearReserva_Fallo_CanchaExactaSolapada() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        Reserva reservaExistente = Reserva.builder()
                .id(2L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(fechaInicio.minusMinutes(30))
                .fechaHoraFin(fechaInicio.plusMinutes(30))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any())).thenReturn(List.of(reservaExistente));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );

        // Assert
        assert exception.getMessage().equals("La cancha exacta ya está reservada en ese horario");
    }

    @Test
    @DisplayName("crearReserva_Exito_ReservaPegada")
    void crearReserva_Exito_ReservaPegada() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(11, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(12, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        Reserva reservaGuardada = Reserva.builder()
                .id(3L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = assertDoesNotThrow(() -> reservaService.crearReserva(request, jugador.getEmail()));

        // Assert
        assert response != null;
        verify(reservaRepository).save(any(Reserva.class));
    }

    @Test
    @DisplayName("crearReserva_Fallo_PoolCanchasAgotado")
    void crearReserva_Fallo_PoolCanchasAgotado() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);

        Cancha canchaFisicaUno = Cancha.builder()
                .id(1L)
                .nombre("Cancha F5 1")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(300))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        Cancha canchaFisicaDos = Cancha.builder()
                .id(2L)
                .nombre("Cancha F5 2")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(300))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        Cancha canchaLogica = Cancha.builder()
                .id(200L)
                .nombre("Cancha F7")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(14)
                .precioBase(BigDecimal.valueOf(2000))
                .montoSena(BigDecimal.valueOf(700))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>(List.of(canchaFisicaUno, canchaFisicaDos)))
                .canchasNecesarias(2)
                .build();

        Reserva reservaFisicaUno = Reserva.builder()
                .id(4L)
                .jugador(jugador)
                .cancha(canchaFisicaUno)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.valueOf(300))
                .build();

        Reserva reservaFisicaDos = Reserva.builder()
                .id(5L)
                .jugador(jugador)
                .cancha(canchaFisicaDos)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.valueOf(300))
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(canchaLogica.getId())).thenReturn(Optional.of(canchaLogica));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any())).thenReturn(List.of(reservaFisicaUno, reservaFisicaDos));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(canchaLogica, canchaFisicaUno, canchaFisicaDos));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(new ReservaRequest(canchaLogica.getId(), fechaInicio, fechaFin, Deporte.FUTBOL), jugador.getEmail())
        );

        // Assert
        assert exception.getMessage().equals("No hay disponibilidad en el pool para armar esta cancha");
    }

    @Test
    @DisplayName("crearReserva_Fallo_CanchaBloqueada")
    void crearReserva_Fallo_CanchaBloqueada() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha bloqueo =
                com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha.builder()
                        .id(1L)
                        .cancha(cancha)
                        .fechaInicio(fechaInicio)
                        .fechaFin(fechaFin)
                        .motivo("Mantenimiento")
                        .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(bloqueoCanchaRepository.findOverlappingBloqueos(cancha.getId(), fechaInicio, fechaFin))
                .thenReturn(List.of(bloqueo));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );

        // Assert
        assert exception.getMessage().contains("La cancha se encuentra bloqueada en ese horario");
    }

    @Test
    @DisplayName("crearReserva_Exito_HorarioCruzaMedianoche")
    void crearReserva_Exito_HorarioCruzaMedianoche() {
        // Arrange
        canchasInEstablecimientoWithHorarioNocturno();
        cancha.setDuracionesPermitidas(new ArrayList<>(List.of(120)));

        LocalDateTime fechaInicio = FECHA_BASE.atTime(23, 0);
        LocalDateTime fechaFin = FECHA_BASE.plusDays(1).atTime(1, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        Reserva reservaGuardada = Reserva.builder()
                .id(10L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(3000))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(bloqueoCanchaRepository.findOverlappingBloqueos(cancha.getId(), fechaInicio, fechaFin))
                .thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any()))
                .thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = assertDoesNotThrow(() -> reservaService.crearReserva(request, jugador.getEmail()));

        // Assert
        assert response != null;
        verify(reservaRepository).save(any(Reserva.class));
    }

    @Test
    @DisplayName("crearReserva_Fallo_HorarioFueraDeServicioCruzaMedianoche")
    void crearReserva_Fallo_HorarioFueraDeServicioCruzaMedianoche() {
        // Arrange
        establecimiento.setHorariosAtencion(List.of(
                HorarioAtencion.builder()
                        .diaSemana(java.time.DayOfWeek.TUESDAY)
                        .horaApertura(LocalTime.of(20, 0))
                        .horaCierre(LocalTime.of(2, 0))
                        .establecimiento(establecimiento)
                        .build()
        ));

        LocalDateTime fechaInicio = FECHA_BASE.atTime(3, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(4, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(bloqueoCanchaRepository.findOverlappingBloqueos(cancha.getId(), fechaInicio, fechaFin))
                .thenReturn(List.of());

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );

        // Assert
        assert exception.getMessage().contains("El horario solicitado se encuentra fuera del horario de atención");
    }

    @Test
    @DisplayName("crearReservaManual_Exito_QuedaConfirmadaYSinJugador")
    void crearReservaManual_Exito_QuedaConfirmadaYSinJugador() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaManualRequest request = new ReservaManualRequest(
                cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL, "Cliente Mostrador", "1122334455", false);

        Reserva reservaGuardada = Reserva.builder()
                .id(20L)
                .jugador(null)
                .cancha(cancha)
                .nombreClienteManual("Cliente Mostrador")
                .telefonoClienteManual("1122334455")
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = assertDoesNotThrow(() -> reservaService.crearReservaManual(request, dueno.getEmail()));

        // Assert
        assert response != null;
        assert response.estado().equals("CONFIRMADA");
        assert response.jugadorId() == null;
        assert response.nombreClienteManual().equals("Cliente Mostrador");
        verify(reservaRepository).save(argThat(r -> r.getEstado() == EstadoReserva.CONFIRMADA && r.getJugador() == null));
        // El bloqueo de jugadores solo aplica al autoservicio (crearReserva), no a las
        // reservas de mostrador que carga el propio dueño.
        verify(bloqueoJugadorRepository, never()).existsByEstablecimientoIdAndJugadorId(any(), any());
        verify(eventPublisher).publishEvent(new ReservaConfirmadaEvent(reservaGuardada.getId()));
    }

    @Test
    @DisplayName("crearReservaManual_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearReservaManual_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
        ReservaManualRequest request = new ReservaManualRequest(
                cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL, "Cliente Mostrador", null, null);

        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .password("password")
                .nombre("Otro Dueño")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(autorizacionEmpleadoService.validarAccion(any(), any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado para realizar esta acción en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.crearReservaManual(request, otroDueno.getEmail())
        );
    }

    @Test
    @DisplayName("confirmarReserva_Exito_PublicaEventoDeReservaConfirmada")
    void confirmarReserva_Exito_PublicaEventoDeReservaConfirmada() {
        // Arrange
        Reserva reservaPendiente = Reserva.builder()
                .id(40L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .expiraEn(LocalDateTime.now().plusMinutes(5))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaPendiente.getId()))
                .thenReturn(Optional.of(reservaPendiente));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.confirmarReserva(reservaPendiente.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("CONFIRMADA");
        ArgumentCaptor<ReservaConfirmadaEvent> captor = ArgumentCaptor.forClass(ReservaConfirmadaEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(reservaPendiente.getId(), captor.getValue().reservaId());
    }

    @Test
    @DisplayName("confirmarReserva_YaConfirmada_NoPublicaEventoPorSerIdempotente")
    void confirmarReserva_YaConfirmada_NoPublicaEventoPorSerIdempotente() {
        // Arrange
        Reserva reservaYaConfirmada = Reserva.builder()
                .id(41L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaYaConfirmada.getId()))
                .thenReturn(Optional.of(reservaYaConfirmada));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.confirmarReserva(reservaYaConfirmada.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("CONFIRMADA");
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("crearReservaSemanal_Exito_GeneraUnaReservaConfirmadaPorFecha")
    void crearReservaSemanal_Exito_GeneraUnaReservaConfirmadaPorFecha() {
        // Arrange: martes 08, 15 y 22 de enero de 2030 (3 ocurrencias)
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL, null, "Cliente Fijo", "1122334455");

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.saveAll(any(List.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        List<ReservaResponse> responses = assertDoesNotThrow(
                () -> reservaService.crearReservaSemanal(request, dueno.getEmail()));

        // Assert
        assert responses.size() == 3;
        assert responses.stream().allMatch(r -> r.estado().equals("CONFIRMADA"));
        assert responses.stream().allMatch(r -> r.jugadorId() == null);
        assert responses.stream().allMatch(r -> r.nombreClienteManual().equals("Cliente Fijo"));
        verify(reservaRepository).saveAll(argThat(list -> ((List<?>) list).size() == 3));
        // El bloqueo de jugadores solo aplica al autoservicio (crearReserva): el dueño puede
        // cargarle igual un turno fijo semanal a un jugador que él mismo haya bloqueado.
        verify(bloqueoJugadorRepository, never()).existsByEstablecimientoIdAndJugadorId(any(), any());
        // Una reserva CONFIRMADA por ocurrencia generada -> un evento por ocurrencia.
        verify(eventPublisher, times(3)).publishEvent(any(ReservaConfirmadaEvent.class));
    }

    @Test
    @DisplayName("crearReservaSemanal_Fallo_JugadorIdNoEsRolPlayer")
    void crearReservaSemanal_Fallo_JugadorIdNoEsRolPlayer() {
        // Arrange
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL, dueno.getId(), null, null);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.findById(dueno.getId())).thenReturn(Optional.of(dueno));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReservaSemanal(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("PLAYER");
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crearReservaSemanal_Fallo_TodoONada_UnaFechaBloqueada")
    void crearReservaSemanal_Fallo_TodoONada_UnaFechaBloqueada() {
        // Arrange
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL, null, "Cliente Fijo", null);

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
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of(bloqueo));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReservaSemanal(request, dueno.getEmail())
        );

        // Assert: todo-o-nada, no debe guardarse ninguna reserva aunque las 2 primeras fechas eran válidas
        assert exception.getMessage().contains("2030-01-22");
        assert exception.getMessage().contains("bloqueada");
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crearReservaSemanal_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearReservaSemanal_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 1, 8), LocalDate.of(2030, 1, 22), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.FUTBOL, null, "Cliente Fijo", null);

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
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.crearReservaSemanal(request, otroDueno.getEmail())
        );
    }

    @Test
    @DisplayName("moverReservaDeCancha_Exito_ReasignaCancha")
    void moverReservaDeCancha_Exito_ReasignaCancha() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);

        Reserva reservaOriginal = Reserva.builder()
                .id(30L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Cancha canchaDestino = Cancha.builder()
                .id(300L)
                .nombre("Cancha B")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(canchaRepository.findById(canchaDestino.getId())).thenReturn(Optional.of(canchaDestino));
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(fechaInicio), eq(fechaFin), any()))
                .thenReturn(List.of(reservaOriginal));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha, canchaDestino));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.moverReservaDeCancha(reservaOriginal.getId(), canchaDestino.getId(), dueno.getEmail()));

        // Assert
        assert response.canchaId().equals(canchaDestino.getId());
        verify(reservaRepository).save(argThat(r -> r.getCancha().getId().equals(canchaDestino.getId())));
    }

    @Test
    @DisplayName("moverReservaDeCancha_Fallo_NuevaCanchaDeOtroEstablecimiento")
    void moverReservaDeCancha_Fallo_NuevaCanchaDeOtroEstablecimiento() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);

        Reserva reservaOriginal = Reserva.builder()
                .id(31L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(20L)
                .nombre("Otro Establecimiento")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        Cancha canchaDeOtroEstablecimiento = Cancha.builder()
                .id(301L)
                .nombre("Cancha Otro Predio")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(otroEstablecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(canchaRepository.findById(canchaDeOtroEstablecimiento.getId())).thenReturn(Optional.of(canchaDeOtroEstablecimiento));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.moverReservaDeCancha(reservaOriginal.getId(), canchaDeOtroEstablecimiento.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("mismo establecimiento");
    }

    @Test
    @DisplayName("moverReservaDeCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void moverReservaDeCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        Reserva reservaOriginal = Reserva.builder()
                .id(32L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Usuario otroDueno = Usuario.builder()
                .id(5L)
                .email("otro-dueno-mover@test.com")
                .password("password")
                .nombre("Otro Dueño")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.moverReservaDeCancha(reservaOriginal.getId(), 999L, otroDueno.getEmail())
        );
    }

    @Test
    @DisplayName("moverReservaDeCancha_Fallo_ReservaCancelada")
    void moverReservaDeCancha_Fallo_ReservaCancelada() {
        // Arrange
        Reserva reservaCancelada = Reserva.builder()
                .id(33L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaCancelada.getId()))
                .thenReturn(Optional.of(reservaCancelada));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.moverReservaDeCancha(reservaCancelada.getId(), 999L, dueno.getEmail())
        );
        assert exception.getMessage().contains("pendiente de seña o confirmada");
    }

    @Test
    @DisplayName("moverReservaDeCancha_Fallo_ReservaFinalizada")
    void moverReservaDeCancha_Fallo_ReservaFinalizada() {
        // Arrange
        Reserva reservaFinalizada = Reserva.builder()
                .id(34L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaFinalizada.getId()))
                .thenReturn(Optional.of(reservaFinalizada));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.moverReservaDeCancha(reservaFinalizada.getId(), 999L, dueno.getEmail())
        );
        assert exception.getMessage().contains("pendiente de seña o confirmada");
    }

    @Test
    @DisplayName("crearReserva_Fallo_DiaNoLaborable")
    void crearReserva_Fallo_DiaNoLaborable() {
        // Arrange
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
.fecha(FECHA_BASE)
                .motivo("Feriado nacional")
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFecha(establecimiento.getId(), FECHA_BASE))
                .thenReturn(Optional.of(diaNoLaborable));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );
        assert exception.getMessage().contains("no abre");
        assert exception.getMessage().contains("Feriado nacional");
    }

    @Test
    @DisplayName("crearReservaSemanal_Fallo_TodoONada_DiaNoLaborableEnUnaFecha")
    void crearReservaSemanal_Fallo_TodoONada_DiaNoLaborableEnUnaFecha() {
        // Arrange: martes 08, 15 y 22 de enero de 2030; el 15 es feriado
        LocalDate fechaInicioPeriodo = LocalDate.of(2030, 1, 8);
        LocalDate fechaFinPeriodo = LocalDate.of(2030, 1, 22);
        LocalTime horaInicio = LocalTime.of(20, 0);
        LocalTime horaFin = LocalTime.of(21, 0);

        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), fechaInicioPeriodo, fechaFinPeriodo, DayOfWeek.TUESDAY,
                horaInicio, horaFin, Deporte.FUTBOL, null, "Cliente Fijo", null);

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.of(2030, 1, 15))
                .motivo("Feriado nacional")
                .build();

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of(diaNoLaborable));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());

        // Act & Assert: todo-o-nada, no debe guardarse nada aunque el 08 era válido
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReservaSemanal(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("2030-01-15");
        assert exception.getMessage().contains("Feriado nacional");
        verify(reservaRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("crearReserva_Fallo_DeporteNoSoportadoPorLaCancha")
    void crearReserva_Fallo_DeporteNoSoportadoPorLaCancha() {
        // Arrange: la cancha solo tiene habilitado FUTBOL
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.HOCKEY);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );
        assert exception.getMessage().contains("HOCKEY");
    }

    @Test
    @DisplayName("crearReservaManual_Fallo_DeporteNoSoportadoPorLaCancha")
    void crearReservaManual_Fallo_DeporteNoSoportadoPorLaCancha() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
        ReservaManualRequest request = new ReservaManualRequest(
                cancha.getId(), fechaInicio, fechaFin, Deporte.PADEL, "Cliente Mostrador", null, null);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReservaManual(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("PADEL");
    }

    @Test
    @DisplayName("crearReservaSemanal_Fallo_DeporteNoSoportadoPorLaCancha")
    void crearReservaSemanal_Fallo_DeporteNoSoportadoPorLaCancha() {
        // Arrange
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                cancha.getId(), LocalDate.of(2030, 1, 8), LocalDate.of(2030, 1, 22), DayOfWeek.TUESDAY,
                LocalTime.of(20, 0), LocalTime.of(21, 0), Deporte.TENIS, null, "Cliente Fijo", null);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReservaSemanal(request, dueno.getEmail())
        );
        assert exception.getMessage().contains("TENIS");
    }

    @Test
    @DisplayName("moverReservaDeCancha_Fallo_CanchaDestinoNoSoportaElDeporte")
    void moverReservaDeCancha_Fallo_CanchaDestinoNoSoportaElDeporte() {
        // Arrange: la reserva original es de HOCKEY, la cancha destino solo tiene FUTBOL
        Reserva reservaOriginal = Reserva.builder()
                .id(40L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.HOCKEY)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Cancha canchaDestinoSinHockey = Cancha.builder()
                .id(400L)
                .nombre("Cancha Solo Futbol")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(new ArrayList<>(List.of(60)))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(canchaRepository.findById(canchaDestinoSinHockey.getId())).thenReturn(Optional.of(canchaDestinoSinHockey));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.moverReservaDeCancha(reservaOriginal.getId(), canchaDestinoSinHockey.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("HOCKEY");
    }

    @Test
    @DisplayName("finalizarReserva_Exito_CambiaEstadoAFinalizada")
    void finalizarReserva_Exito_CambiaEstadoAFinalizada() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(50L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.finalizarReserva(reservaConfirmada.getId(), MetodoPago.EFECTIVO, dueno.getEmail()));

        // Assert
        assert response.estado().equals("FINALIZADA");
        verify(reservaRepository).save(argThat(r -> r.getEstado() == EstadoReserva.FINALIZADA && r.getMetodoPago() == MetodoPago.EFECTIVO));
        // montoCobrado = precioTotal (1500) - senaPagada (500) = 1000
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.INGRESO), eq(OrigenMovimientoCaja.RESERVA),
                eq(MetodoPago.EFECTIVO), eq(BigDecimal.valueOf(1000)), eq("Reserva #" + reservaConfirmada.getId() + " finalizada"),
                eq(reservaConfirmada.getId()), eq(dueno));
    }

    @Test
    @DisplayName("finalizarReserva_Exito_MetodoPagoNoEfectivo_IgualLlamaAlHook")
    void finalizarReserva_Exito_MetodoPagoNoEfectivo_IgualLlamaAlHook() {
        // Arrange: registrarMovimientoSiCorresponde es el único punto de decisión sobre si el
        // pago fue en efectivo o no (ver TurnoCajaService); ReservaService siempre lo llama
        // cuando hay saldo positivo, delegando ese no-op interno.
        Reserva reservaConfirmada = Reserva.builder()
                .id(55L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        assertDoesNotThrow(
                () -> reservaService.finalizarReserva(reservaConfirmada.getId(), MetodoPago.TARJETA_CREDITO, dueno.getEmail()));

        // Assert
        verify(turnoCajaService).registrarMovimientoSiCorresponde(
                eq(establecimiento), eq(TipoMovimientoCaja.INGRESO), eq(OrigenMovimientoCaja.RESERVA),
                eq(MetodoPago.TARJETA_CREDITO), eq(BigDecimal.valueOf(1000)), eq("Reserva #" + reservaConfirmada.getId() + " finalizada"),
                eq(reservaConfirmada.getId()), eq(dueno));
    }

    @Test
    @DisplayName("finalizarReserva_Exito_MontoCobradoCero_NoLlamaAlHook")
    void finalizarReserva_Exito_MontoCobradoCero_NoLlamaAlHook() {
        // Arrange: la seña ya cubrió el precio total completo (por ejemplo un turno fijo
        // sin saldo pendiente), no hay nada más que cobrar al finalizar.
        Reserva reservaConfirmada = Reserva.builder()
                .id(56L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(1500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        assertDoesNotThrow(
                () -> reservaService.finalizarReserva(reservaConfirmada.getId(), MetodoPago.EFECTIVO, dueno.getEmail()));

        // Assert
        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("finalizarReserva_Exito_EsIdempotenteSiYaEstaFinalizada")
    void finalizarReserva_Exito_EsIdempotenteSiYaEstaFinalizada() {
        // Arrange: ya estaba finalizada con un método de pago distinto al que se manda ahora
        Reserva reservaFinalizada = Reserva.builder()
                .id(51L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .metodoPago(MetodoPago.EFECTIVO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaFinalizada.getId()))
                .thenReturn(Optional.of(reservaFinalizada));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.finalizarReserva(reservaFinalizada.getId(), MetodoPago.TRANSFERENCIA, dueno.getEmail()));

        // Assert: no-op idempotente, ni el estado ni el método de pago original cambian
        assert response.estado().equals("FINALIZADA");
        assert response.metodoPago().equals("EFECTIVO");
        verify(reservaRepository, never()).save(any());
        // El path idempotente no debe generar un movimiento de caja duplicado.
        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("finalizarReserva_Fallo_ReservaCancelada")
    void finalizarReserva_Fallo_ReservaCancelada() {
        // Arrange
        Reserva reservaCancelada = Reserva.builder()
                .id(52L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaCancelada.getId()))
                .thenReturn(Optional.of(reservaCancelada));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.finalizarReserva(reservaCancelada.getId(), MetodoPago.EFECTIVO, dueno.getEmail())
        );
        assert exception.getMessage().contains("cancelada");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("finalizarReserva_Fallo_ReservaPendienteDeSena")
    void finalizarReserva_Fallo_ReservaPendienteDeSena() {
        // Arrange
        Reserva reservaPendiente = Reserva.builder()
                .id(54L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaPendiente.getId()))
                .thenReturn(Optional.of(reservaPendiente));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.finalizarReserva(reservaPendiente.getId(), MetodoPago.EFECTIVO, dueno.getEmail())
        );
        assert exception.getMessage().contains("confirmada");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("finalizarReserva_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void finalizarReserva_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(53L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Usuario otroDueno = Usuario.builder().id(6L).email("otro-final@test.com").rol(Role.OWNER).build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(autorizacionEmpleadoService.validarAccion(any(), any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado para realizar esta acción en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.finalizarReserva(reservaConfirmada.getId(), MetodoPago.EFECTIVO, otroDueno.getEmail())
        );
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelarReserva_Exito_EmpleadoConPermisoCancelarReserva")
    void cancelarReserva_Exito_EmpleadoConPermisoCancelarReserva() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(7L)
                .email("empleado-uuid@empleados.interno")
                .nombre("Empleado Mostrador")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.CANCELAR_RESERVA))
                .isActive(true)
                .build();

        Reserva reservaConfirmada = Reserva.builder()
                .id(60L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(usuarioRepository.findByEmail(empleado.getEmail())).thenReturn(Optional.of(empleado));
        when(autorizacionEmpleadoService.tienePermiso(empleado, establecimiento,
                com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.CANCELAR_RESERVA)).thenReturn(true);
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.cancelarReserva(reservaConfirmada.getId(), empleado.getEmail()));

        // Assert
        assert response.estado().equals("CANCELADA");
        verify(registroAuditoriaService).registrar(
                eq(empleado), eq(com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria.CANCELAR_RESERVA),
                eq(reservaConfirmada.getId()), eq(true), any());
        ArgumentCaptor<ReservaCanceladaEvent> captor = ArgumentCaptor.forClass(ReservaCanceladaEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(reservaConfirmada.getId(), captor.getValue().reservaId());
        assertEquals(empleado.getId(), captor.getValue().actorId());
    }

    @Test
    @DisplayName("cancelarReserva_YaCancelada_NoPublicaEventoPorSerIdempotente")
    void cancelarReserva_YaCancelada_NoPublicaEventoPorSerIdempotente() {
        // Arrange
        Reserva reservaYaCancelada = Reserva.builder()
                .id(62L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaYaCancelada.getId()))
                .thenReturn(Optional.of(reservaYaCancelada));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.cancelarReserva(reservaYaCancelada.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("CANCELADA");
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("cancelarReserva_Fallo_EmpleadoSinPermisoCancelarReserva")
    void cancelarReserva_Fallo_EmpleadoSinPermisoCancelarReserva() {
        // Arrange
        Usuario empleadoSinPermiso = Usuario.builder()
                .id(8L)
                .email("empleado-sin-permiso@empleados.interno")
                .nombre("Empleado Sin Permiso")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of())
                .isActive(true)
                .build();

        Reserva reservaConfirmada = Reserva.builder()
                .id(61L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(usuarioRepository.findByEmail(empleadoSinPermiso.getEmail())).thenReturn(Optional.of(empleadoSinPermiso));
        when(autorizacionEmpleadoService.tienePermiso(empleadoSinPermiso, establecimiento,
                com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.CANCELAR_RESERVA)).thenReturn(false);

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.cancelarReserva(reservaConfirmada.getId(), empleadoSinPermiso.getEmail())
        );
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("obtenerMisReservas_SinFiltroEstado_DebeRetornarReservasDelJugador")
    void obtenerMisReservas_SinFiltroEstado_DebeRetornarReservasDelJugador() {
        // Arrange
        Reserva reserva = Reserva.builder()
                .id(70L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageReservas = new PageImpl<>(List.of(reserva), pageable, 1);
        ReservaResponse response = new ReservaResponse(
                reserva.getId(), jugador.getId(), jugador.getNombre(), cancha.getId(), cancha.getNombre(),
                reserva.getFechaHoraInicio(), reserva.getFechaHoraFin(), "CONFIRMADA",
                reserva.getPrecioTotal(), reserva.getSenaPagada(), null, null, null, null, null);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(reservaRepository.findByJugadorId(jugador.getId(), pageable)).thenReturn(pageReservas);
        when(reservaMapper.mapToResponse(reserva)).thenReturn(response);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerMisReservas(jugador.getEmail(), null, pageable);

        // Assert
        assert resultado.getTotalElements() == 1;
        assert resultado.getContent().get(0).id().equals(70L);
        verify(reservaRepository).findByJugadorId(jugador.getId(), pageable);
        verify(reservaRepository, never()).findByJugadorIdAndEstado(any(), any(), any());
    }

    @Test
    @DisplayName("obtenerMisReservas_ConFiltroEstado_DebeUsarQueryFiltrada")
    void obtenerMisReservas_ConFiltroEstado_DebeUsarQueryFiltrada() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageVacia = new PageImpl<>(List.of(), pageable, 0);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(reservaRepository.findByJugadorIdAndEstado(jugador.getId(), EstadoReserva.CANCELADA, pageable))
                .thenReturn(pageVacia);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerMisReservas(jugador.getEmail(), EstadoReserva.CANCELADA, pageable);

        // Assert
        assert resultado.getTotalElements() == 0;
        verify(reservaRepository).findByJugadorIdAndEstado(jugador.getId(), EstadoReserva.CANCELADA, pageable);
        verify(reservaRepository, never()).findByJugadorId(any(), any());
    }

    @Test
    @DisplayName("obtenerReservasPorCanchaYFecha_PorDefecto_ExcluyeCanceladas")
    void obtenerReservasPorCanchaYFecha_PorDefecto_ExcluyeCanceladas() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageVacia = new PageImpl<>(List.of(), pageable, 0);
        LocalDate fecha = LocalDate.of(2030, 1, 15);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        List<EstadoReserva> estadosCancelados = List.of(EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA);
        when(reservaRepository.findReservasEnRangoDiario(
                eq(cancha.getId()), any(), any(), eq(estadosCancelados), eq(pageable)))
                .thenReturn(pageVacia);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerReservasPorCanchaYFecha(
                cancha.getId(), fecha, false, pageable, dueno.getEmail());

        // Assert
        assert resultado.getTotalElements() == 0;
        verify(reservaRepository).findReservasEnRangoDiario(eq(cancha.getId()), any(), any(), eq(estadosCancelados), eq(pageable));
        verify(reservaRepository, never()).findReservasEnRangoDiarioIncluyendoCanceladas(any(), any(), any(), any());
    }

    @Test
    @DisplayName("obtenerReservasPorCanchaYFecha_IncluirCanceladas_UsaQuerySinFiltro")
    void obtenerReservasPorCanchaYFecha_IncluirCanceladas_UsaQuerySinFiltro() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageVacia = new PageImpl<>(List.of(), pageable, 0);
        LocalDate fecha = LocalDate.of(2030, 1, 15);

        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(reservaRepository.findReservasEnRangoDiarioIncluyendoCanceladas(eq(cancha.getId()), any(), any(), eq(pageable)))
                .thenReturn(pageVacia);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerReservasPorCanchaYFecha(
                cancha.getId(), fecha, true, pageable, dueno.getEmail());

        // Assert
        assert resultado.getTotalElements() == 0;
        verify(reservaRepository).findReservasEnRangoDiarioIncluyendoCanceladas(eq(cancha.getId()), any(), any(), eq(pageable));
        verify(reservaRepository, never()).findReservasEnRangoDiario(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("obtenerReservasPorEstablecimientoYFecha_PorDefecto_ExcluyeCanceladas")
    void obtenerReservasPorEstablecimientoYFecha_PorDefecto_ExcluyeCanceladas() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageVacia = new PageImpl<>(List.of(), pageable, 0);
        LocalDate fecha = LocalDate.of(2030, 1, 15);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        List<EstadoReserva> estadosCancelados = List.of(EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA);
        when(reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(
                eq(establecimiento.getId()), any(), any(), eq(estadosCancelados), eq(pageable)))
                .thenReturn(pageVacia);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerReservasPorEstablecimientoYFecha(
                establecimiento.getId(), fecha, false, pageable, dueno.getEmail());

        // Assert
        assert resultado.getTotalElements() == 0;
        verify(reservaRepository).findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(
                eq(establecimiento.getId()), any(), any(), eq(estadosCancelados), eq(pageable));
        verify(reservaRepository, never()).findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(any(), any(), any(), any());
    }

    @Test
    @DisplayName("obtenerReservasPorEstablecimientoYFecha_IncluirCanceladas_UsaQuerySinFiltro")
    void obtenerReservasPorEstablecimientoYFecha_IncluirCanceladas_UsaQuerySinFiltro() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 10);
        Page<Reserva> pageVacia = new PageImpl<>(List.of(), pageable, 0);
        LocalDate fecha = LocalDate.of(2030, 1, 15);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(reservaRepository.findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(
                eq(establecimiento.getId()), any(), any(), eq(pageable)))
                .thenReturn(pageVacia);

        // Act
        Page<ReservaResponse> resultado = reservaService.obtenerReservasPorEstablecimientoYFecha(
                establecimiento.getId(), fecha, true, pageable, dueno.getEmail());

        // Assert
        assert resultado.getTotalElements() == 0;
        verify(reservaRepository).findByCancha_Establecimiento_IdAndFechaHoraInicioBetween(
                eq(establecimiento.getId()), any(), any(), eq(pageable));
        verify(reservaRepository, never()).findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn(any(), any(), any(), any(), any());
    }

    private void canchasInEstablecimientoWithHorarioNocturno() {
        establecimiento.setHorariosAtencion(List.of(
                HorarioAtencion.builder()
                        .diaSemana(java.time.DayOfWeek.TUESDAY)
                        .horaApertura(LocalTime.of(20, 0))
                        .horaCierre(LocalTime.of(2, 0))
                        .establecimiento(establecimiento)
                        .build()
        ));
    }
}
