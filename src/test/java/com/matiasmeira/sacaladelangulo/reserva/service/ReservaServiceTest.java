package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para ReservaService.
 * Cubre los casos de uso principal: creación de reservas con validaciones.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservaService - Tests de Creación de Reservas")
class ReservaServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private ReservaService reservaService;

    private Usuario jugador;
    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;
    private ReservaRequest reservaRequest;

    @BeforeEach
    void setUp() {
        // Configurar datos de prueba
        jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .password("hashed_password")
                .nombre("Juan Jugador")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .password("hashed_password")
                .nombre("Carlos Dueño")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();

        establecimiento = Establecimiento.builder()
                .id(1L)
                .nombre("Cancha Central")
                .direccion("Calle Principal 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        cancha = Cancha.builder()
                .id(1L)
                .nombre("Cancha A")
                .deporte("Fútbol")
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(500))
                .duracionesPermitidas(List.of(60, 90))
                .permiteInicioMediaHora(false)
                .establecimiento(establecimiento)
                .isActive(true)
                .tarifas(new ArrayList<>())
                .canchasFisicas(new ArrayList<>())
                .build();

        // Request por defecto: reserva válida para hoy + 2 días a las 10:00 - 11:00
        LocalDateTime fechaInicio = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fechaFin = fechaInicio.plusHours(1);

        reservaRequest = new ReservaRequest(
                cancha.getId(),
                fechaInicio,
                fechaFin
        );
    }

    // ==================== CASOS DE PRUEBA ====================

    @Test
    @DisplayName("crearReserva_Exito - Debe crear reserva cuando datos son válidos y sin solapamiento")
    void crearReserva_Exito() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fechaFin = fechaInicio.plusHours(1);
        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin);

        Usuario jugadorEsperado = jugador;
        Cancha chanchaEsperada = cancha;

        Reserva reservaGuardada = Reserva.builder()
                .id(1L)
                .jugador(jugadorEsperado)
                .cancha(chanchaEsperada)
                .fechaHoraInicio(fechaInicio)
                .fechaHoraFin(fechaFin)
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(
                establecimiento.getId(),
                fechaInicio,
                fechaFin
        )).thenReturn(Collections.emptyList());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha));
        when(reservaRepository.save(any(Reserva.class))).thenReturn(reservaGuardada);

        // Act
        ReservaResponse response = reservaService.crearReserva(request, jugador.getEmail());

        // Assert
        assertNotNull(response);
        assertEquals(1L, response.id());
        assertEquals("PENDIENTE_SENA", response.estado());
        assertEquals(BigDecimal.valueOf(1000), response.precioTotal());

        verify(usuarioRepository).findByEmail(jugador.getEmail());
        verify(canchaRepository).findById(cancha.getId());
        verify(reservaRepository).findSuperpuestas(establecimiento.getId(), fechaInicio, fechaFin);
        verify(reservaRepository).save(any(Reserva.class));
    }

    @Test
    @DisplayName("crearReserva_Falla_FechasInvalidas - Debe lanzar excepción si inicio >= fin")
    void crearReserva_Falla_FechasInvalidas() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.now().plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fechaFin = fechaInicio.minusHours(1); // Fin ANTES que inicio

        ReservaRequest requestInvalido = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(requestInvalido, jugador.getEmail()),
                "Debe lanzar IllegalArgumentException cuando la fecha de inicio >= fin"
        );

        assertTrue(exception.getMessage().contains("anterior a la de fin"));
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearReserva_Falla_EnElPasado - Debe lanzar excepción si reserva es en el pasado")
    void crearReserva_Falla_EnElPasado() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.now().minusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fechaFin = fechaInicio.plusHours(1);

        ReservaRequest requestPasado = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin);

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(requestPasado, jugador.getEmail()),
                "Debe lanzar IllegalArgumentException cuando la reserva es en el pasado"
        );

        assertTrue(exception.getMessage().contains("pasado"));
        verify(reservaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearReserva_Falla_SolapamientoCanchaExacta - Debe lanzar excepción por solapamiento")
    void crearReserva_Falla_SolapamientoCanchaExacta() {
        // Arrange
        LocalDateTime fechaInicio = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime fechaFin = fechaInicio.plusHours(1);

        ReservaRequest request = new ReservaRequest(cancha.getId(), fechaInicio, fechaFin);

        // Crear una reserva existente que se superpone
        Reserva reservaExistente = Reserva.builder()
                .id(99L)
                .jugador(jugador)
                .cancha(cancha) // Misma cancha
                .fechaHoraInicio(fechaInicio.minusMinutes(30))
                .fechaHoraFin(fechaInicio.plusMinutes(30))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(reservaRepository.findSuperpuestas(
                establecimiento.getId(),
                fechaInicio,
                fechaFin
        )).thenReturn(List.of(reservaExistente)); // Retorna reserva solapada

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> reservaService.crearReserva(request, jugador.getEmail()),
                "Debe lanzar IllegalArgumentException por solapamiento de cancha exacta"
        );

        assertTrue(exception.getMessage().contains("ya está reservada"));
        verify(reservaRepository, never()).save(any());
    }

}
