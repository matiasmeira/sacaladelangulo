package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaManualRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ReservaMapper reservaMapper;

    @InjectMocks
    private ReservaService reservaService;

    private Usuario jugador;
    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;

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
                    reserva.getDeporteSeleccionado()
            );
        });
    }

    @Test
    @DisplayName("crearReserva_Exito_SinSolapamiento")
    void crearReserva_Exito_SinSolapamiento() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId())).thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = assertDoesNotThrow(() -> reservaService.crearReserva(request, jugador.getEmail()));

        // Assert
        assert response != null;
        verify(reservaRepository).save(any(Reserva.class));
    }

    @Test
    @DisplayName("crearReserva_Fallo_CanchaExactaSolapada")
    void crearReserva_Fallo_CanchaExactaSolapada() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin)).thenReturn(List.of(reservaExistente));

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
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 11, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 12, 0);
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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin)).thenReturn(List.of());
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
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);

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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin)).thenReturn(List.of(reservaFisicaUno, reservaFisicaDos));
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
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
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

        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 23, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 16, 1, 0);
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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin))
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

        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 3, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 4, 0);
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
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
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

        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin)).thenReturn(List.of());
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

        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> reservaService.crearReservaManual(request, otroDueno.getEmail())
        );
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
        when(bloqueoCanchaRepository.findOverlappingBloqueos(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any())).thenReturn(List.of());
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
        when(bloqueoCanchaRepository.findOverlappingBloqueos(any(), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findOverlappingBloqueos(cancha.getId(), inicioBloqueado, finBloqueado))
                .thenReturn(List.of(bloqueo));

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
        when(reservaRepository.findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin))
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
        assert exception.getMessage().contains("cancelada");
    }

    @Test
    @DisplayName("crearReserva_Fallo_DiaNoLaborable")
    void crearReserva_Fallo_DiaNoLaborable() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.of(2030, 1, 15, 10, 0);
        LocalDateTime fechaFin = LocalDateTime.of(2030, 1, 15, 11, 0);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin, Deporte.FUTBOL);

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.of(2030, 1, 15))
                .motivo("Feriado nacional")
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFecha(establecimiento.getId(), LocalDate.of(2030, 1, 15)))
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
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFecha(any(), any())).thenReturn(Optional.empty());
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFecha(establecimiento.getId(), LocalDate.of(2030, 1, 15)))
                .thenReturn(Optional.of(diaNoLaborable));

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

        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
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
