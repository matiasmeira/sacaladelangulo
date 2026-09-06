package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.JugadorBloqueadoException;
import com.matiasmeira.sacaladelangulo.core.exception.TelefonoNoVerificadoException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
    @DisplayName("crearReserva_Fallo_TelefonoNoVerificado")
    void crearReserva_Fallo_TelefonoNoVerificado() {
        // Arrange
        establecimiento.setRequiereTelefonoVerificado(true);
        jugador.setTelefonoVerificado(false);
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        TelefonoNoVerificadoException exception = assertThrows(
                TelefonoNoVerificadoException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail())
        );
        assert exception.getMessage().contains("teléfono");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearReserva_Exito_TelefonoVerificadoCuandoEsRequerido")
    void crearReserva_Exito_TelefonoVerificadoCuandoEsRequerido() {
        // Arrange
        establecimiento.setRequiereTelefonoVerificado(true);
        jugador.setTelefonoVerificado(true);
        LocalDateTime fechaInicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

        Reserva reservaGuardada = Reserva.builder()
                .id(6L)
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
    @DisplayName("crearReserva_Fallo_DemasiadaAnticipacion")
    void crearReserva_Fallo_DemasiadaAnticipacion() {
        // Arrange: más de 31 días de anticipación (mismo tope que DisponibilidadService)
        LocalDateTime fechaInicio = FECHA_BASE.plusDays(40).atTime(10, 0);
        LocalDateTime fechaFin = FECHA_BASE.plusDays(40).atTime(11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
                () -> reservaService.crearReserva(new ReservaRequest(canchaLogica.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5), jugador.getEmail())
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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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
                cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5, "Cliente Mostrador", "1122334455", false);

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
                cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5, "Cliente Mostrador", null, null);

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
                .deporteSeleccionado(Deporte.FUTBOL_5)
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
                .deporteSeleccionado(Deporte.FUTBOL_5)
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
    @DisplayName("moverReservaDeCancha_Exito_ReasignaCancha")
    void moverReservaDeCancha_Exito_ReasignaCancha() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);

        Reserva reservaOriginal = Reserva.builder()
                .id(30L)
                .jugador(jugador)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Cancha canchaDestino = Cancha.builder()
                .id(300L)
                .nombre("Cancha B")
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

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

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
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL_5);

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

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaOriginal.getId()))
                .thenReturn(Optional.of(reservaOriginal));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
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

    /**
     * El corazón de I-3: lo que importa no es sólo que finalizarReserva lance, sino que
     * NO se cobre. Antes del guard, un no-show pasaba a FINALIZADA y disparaba un
     * movimiento de caja por precioTotal - senaPagada (acá, $1000) sobre un turno que
     * nadie jugó.
     */
    @Test
    @DisplayName("finalizarReserva_Ausente_NoCobraNiMueveLaCaja")
    void finalizarReserva_Ausente_NoCobraNiMueveLaCaja() {
        // Arrange: saldo pendiente > 0, para que si el cobro se ejecutara fuera visible
        Reserva reservaAusente = Reserva.builder()
                .id(65L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));

        // Act
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reservaService.finalizarReserva(reservaAusente.getId(), MetodoPago.EFECTIVO, dueno.getEmail()));

        // Assert: sigue ausente, sin persistir, sin método de pago y -- lo que importa --
        // sin un solo peso registrado en la caja.
        assertTrue(ex.getMessage().contains("ausente"));
        assertEquals(EstadoReserva.AUSENTE, reservaAusente.getEstado());
        assertEquals(null, reservaAusente.getMetodoPago());
        verify(reservaRepository, never()).save(any());
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

    /**
     * El caso que motiva el chequeo de AUSENTE: el jugador es actor autorizado de
     * cancelarReserva, así que sin la validación podía cancelar su propio no-show y
     * borrar el registro de que no se presentó.
     */
    @Test
    @DisplayName("cancelarReserva_AusenteYEsElJugador_LanzaExcepcionYNoBorraLaAusencia")
    void cancelarReserva_AusenteYEsElJugador_LanzaExcepcionYNoBorraLaAusencia() {
        // Arrange: turno pasado marcado como ausente. fechaCreacion = ahora lo deja dentro
        // del período de gracia de validarPlazoDeCancelacion (que para el jugador corre
        // ANTES que el chequeo de estado), así que lo que corta la operación es el estado
        // AUSENTE y no el plazo -- que es justamente lo que este test tiene que probar.
        Reserva reservaAusente = Reserva.builder()
                .id(63L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaCreacion(LocalDateTime.now())
                .fechaHoraInicio(LocalDateTime.now().minusHours(2))
                .fechaHoraFin(LocalDateTime.now().minusHours(1))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));
        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));

        // Act
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> reservaService.cancelarReserva(reservaAusente.getId(), jugador.getEmail()));

        // Assert: la ausencia sigue en pie y no se persistió ni notificó nada.
        assertTrue(ex.getMessage().contains("ausente"));
        assertEquals(EstadoReserva.AUSENTE, reservaAusente.getEstado());
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * Ni siquiera el dueño puede saltarse el paso de revertirAusencia: la salida de
     * AUSENTE es una sola, para que quede asentado que la ausencia se deshizo.
     */
    @Test
    @DisplayName("cancelarReserva_AusenteYEsElDueno_TambienLanzaExcepcion")
    void cancelarReserva_AusenteYEsElDueno_TambienLanzaExcepcion() {
        // Arrange
        Reserva reservaAusente = Reserva.builder()
                .id(64L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.now().minusHours(2))
                .fechaHoraFin(LocalDateTime.now().minusHours(1))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act + Assert
        assertThrows(IllegalArgumentException.class,
                () -> reservaService.cancelarReserva(reservaAusente.getId(), dueno.getEmail()));
        assertEquals(EstadoReserva.AUSENTE, reservaAusente.getEstado());
        verify(reservaRepository, never()).save(any());
    }

    /**
     * I-2: cancelar una reserva ya liberada por vencimiento del hold NO debe reescribirla
     * como CANCELADA. Los dos estados existen para que los reportes puedan separar un
     * abandono (nadie confirmó a tiempo) de una cancelación explícita; colapsarlos hace
     * que toda prereserva vencida que alguien toque desde la UI se contabilice como
     * cancelación del usuario.
     */
    @Test
    @DisplayName("cancelarReserva_YaCanceladaPrereserva_ConservaElEstadoDeVencimiento")
    void cancelarReserva_YaCanceladaPrereserva_ConservaElEstadoDeVencimiento() {
        // Arrange
        Reserva prereservaVencida = Reserva.builder()
                .id(66L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA_PRERESERVA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(prereservaVencida.getId()))
                .thenReturn(Optional.of(prereservaVencida));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.cancelarReserva(prereservaVencida.getId(), dueno.getEmail()));

        // Assert: sigue siendo CANCELADA_PRERESERVA, y al ser un no-op no persiste ni notifica.
        assertEquals("CANCELADA_PRERESERVA", response.estado());
        assertEquals(EstadoReserva.CANCELADA_PRERESERVA, prereservaVencida.getEstado());
        verify(reservaRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
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
    @DisplayName("marcarAusente_Exito_ConfirmadaConInicioPasado_CambiaEstadoAAusente")
    void marcarAusente_Exito_ConfirmadaConInicioPasado_CambiaEstadoAAusente() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(70L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.marcarAusente(reservaConfirmada.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("AUSENTE");
        verify(reservaRepository).save(argThat(r -> r.getEstado() == EstadoReserva.AUSENTE));
        verify(turnoCajaService, never()).registrarMovimientoSiCorresponde(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("marcarAusente_Exito_EsIdempotenteSiYaEstaAusente")
    void marcarAusente_Exito_EsIdempotenteSiYaEstaAusente() {
        // Arrange
        Reserva reservaAusente = Reserva.builder()
                .id(71L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.marcarAusente(reservaAusente.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("AUSENTE");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("marcarAusente_Fallo_TurnoFuturo")
    void marcarAusente_Fallo_TurnoFuturo() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(72L)
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

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.marcarAusente(reservaConfirmada.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("todavía no empezó");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("marcarAusente_Fallo_ReservaPendienteDeSena")
    void marcarAusente_Fallo_ReservaPendienteDeSena() {
        // Arrange
        Reserva reservaPendiente = Reserva.builder()
                .id(73L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaPendiente.getId()))
                .thenReturn(Optional.of(reservaPendiente));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.marcarAusente(reservaPendiente.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("CONFIRMADA");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("marcarAusente_Fallo_ReservaCancelada")
    void marcarAusente_Fallo_ReservaCancelada() {
        // Arrange
        Reserva reservaCancelada = Reserva.builder()
                .id(74L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaCancelada.getId()))
                .thenReturn(Optional.of(reservaCancelada));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.marcarAusente(reservaCancelada.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("CONFIRMADA");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("marcarAusente_Fallo_ReservaFinalizada")
    void marcarAusente_Fallo_ReservaFinalizada() {
        // Arrange
        Reserva reservaFinalizada = Reserva.builder()
                .id(75L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(1500))
                .metodoPago(MetodoPago.EFECTIVO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaFinalizada.getId()))
                .thenReturn(Optional.of(reservaFinalizada));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.marcarAusente(reservaFinalizada.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("CONFIRMADA");
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("marcarAusente_Fallo_EmpleadoSinPermisoMarcarAusente")
    void marcarAusente_Fallo_EmpleadoSinPermisoMarcarAusente() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(76L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Usuario empleadoSinPermiso = Usuario.builder()
                .id(9L)
                .email("empleado-sin-permiso-ausente@empleados.interno")
                .nombre("Empleado Sin Permiso")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of())
                .isActive(true)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, empleadoSinPermiso.getEmail(),
                com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.MARCAR_AUSENTE))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado para realizar esta acción en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.marcarAusente(reservaConfirmada.getId(), empleadoSinPermiso.getEmail())
        );
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("revertirAusencia_Exito_OwnerVuelveAConfirmada")
    void revertirAusencia_Exito_OwnerVuelveAConfirmada() {
        // Arrange
        Reserva reservaAusente = Reserva.builder()
                .id(80L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(reservaRepository.save(any(Reserva.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReservaResponse response = assertDoesNotThrow(
                () -> reservaService.revertirAusencia(reservaAusente.getId(), dueno.getEmail()));

        // Assert
        assert response.estado().equals("CONFIRMADA");
        verify(reservaRepository).save(argThat(r -> r.getEstado() == EstadoReserva.CONFIRMADA));
    }

    @Test
    @DisplayName("revertirAusencia_Fallo_Empleado_AunqueTengaPermisoMarcarAusente")
    void revertirAusencia_Fallo_Empleado_AunqueTengaPermisoMarcarAusente() {
        // Arrange: validarPropietarioOAdmin no tiene excepción para empleados (a diferencia
        // de validarAccion), así que un EMPLOYEE con MARCAR_AUSENTE igual queda afuera.
        Reserva reservaAusente = Reserva.builder()
                .id(81L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.AUSENTE)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        Usuario empleadoConPermiso = Usuario.builder()
                .id(10L)
                .email("empleado-con-permiso-ausente@empleados.interno")
                .nombre("Empleado Con Permiso")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.MARCAR_AUSENTE))
                .isActive(true)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaAusente.getId()))
                .thenReturn(Optional.of(reservaAusente));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, empleadoConPermiso.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.revertirAusencia(reservaAusente.getId(), empleadoConPermiso.getEmail())
        );
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("revertirAusencia_Fallo_EstadoNoEsAusente")
    void revertirAusencia_Fallo_EstadoNoEsAusente() {
        // Arrange
        Reserva reservaConfirmada = Reserva.builder()
                .id(82L)
                .jugador(jugador)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2020, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2020, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(reservaConfirmada.getId()))
                .thenReturn(Optional.of(reservaConfirmada));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.revertirAusencia(reservaConfirmada.getId(), dueno.getEmail())
        );
        assert exception.getMessage().contains("ausente");
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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
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
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
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
        when(autorizacionEmpleadoService.validarLectura(eq(establecimiento), eq(dueno.getEmail()), any())).thenReturn(dueno);
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
        when(autorizacionEmpleadoService.validarLectura(eq(establecimiento), eq(dueno.getEmail()), any())).thenReturn(dueno);
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
