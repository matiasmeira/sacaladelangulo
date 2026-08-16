package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TipoEliminacionCuenta;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EstablecimientosActivosException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaCanceladaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioEliminacionService - Baja de cuenta (soft-delete + anonimizacion)")
class UsuarioEliminacionServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UsuarioEliminacionService usuarioEliminacionService;

    private Usuario jugador;

    @BeforeEach
    void setUp() {
        jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan Jugador")
                .telefono("11122233")
                .password("hash-viejo")
                .rol(Role.PLAYER)
                .isActive(true)
                .tokenVersion(2)
                .aceptaMarketing(true)
                .build();
    }

    @Test
    @DisplayName("autoeliminar_PasswordCorrecta_AnonimizaUsuarioYPublicaEventoDeConfirmacion")
    void autoeliminar_PasswordCorrecta_AnonimizaUsuarioYPublicaEventoDeConfirmacion() {
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(reservaRepository.findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(eq(1L), any(), any())).thenReturn(List.of());

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        Usuario guardado = usuarioCaptor.getValue();
        assertEquals("deleted+1@saque.deleted", guardado.getEmail());
        assertEquals("Usuario eliminado", guardado.getNombre());
        assertNull(guardado.getTelefono());
        assertEquals("hash-random", guardado.getPassword());
        assertEquals(false, guardado.getAceptaMarketing());
        assertEquals(false, guardado.getIsActive());
        assertNotNull(guardado.getDeletedAt());
        assertEquals(3, guardado.getTokenVersion());

        ArgumentCaptor<String> passwordAEncodearCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(passwordAEncodearCaptor.capture());
        String passwordAEncodear = passwordAEncodearCaptor.getValue();
        assertNotEquals("Password123", passwordAEncodear);
        assertNotEquals("jugador@test.com", passwordAEncodear);
        assertDoesNotThrow(() -> java.util.UUID.fromString(passwordAEncodear));

        ArgumentCaptor<AuditoriaEliminacionUsuario> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaEliminacionUsuario.class);
        verify(auditoriaEliminacionUsuarioRepository).save(auditoriaCaptor.capture());
        assertEquals(TipoEliminacionCuenta.AUTOELIMINACION, auditoriaCaptor.getValue().getTipo());
        assertEquals(jugador, auditoriaCaptor.getValue().getUsuario());
        assertNull(auditoriaCaptor.getValue().getActorId());

        ArgumentCaptor<CuentaEliminadaEvent> eventoCaptor = ArgumentCaptor.forClass(CuentaEliminadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("jugador@test.com", eventoCaptor.getValue().email());
        assertEquals("Juan Jugador", eventoCaptor.getValue().nombre());
    }

    @Test
    @DisplayName("autoeliminar_PasswordIncorrecta_LanzaBadCredentialsExceptionYNoModificaNada")
    void autoeliminar_PasswordIncorrecta_LanzaBadCredentialsExceptionYNoModificaNada() {
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Incorrecta", "hash-viejo")).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "Incorrecta"));

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("autoeliminar_RolEmployee_LanzaAccessDeniedException")
    void autoeliminar_RolEmployee_LanzaAccessDeniedException() {
        jugador.setRol(Role.EMPLOYEE);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        assertThrows(AccessDeniedException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_RolAdmin_LanzaAccessDeniedException")
    void autoeliminar_RolAdmin_LanzaAccessDeniedException() {
        jugador.setRol(Role.ADMIN);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        assertThrows(AccessDeniedException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_OwnerConEstablecimientosActivos_LanzaEstablecimientosActivosException")
    void autoeliminar_OwnerConEstablecimientosActivos_LanzaEstablecimientosActivosException() {
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L))
                .thenReturn(List.of(Establecimiento.builder().id(10L).build()));

        assertThrows(EstablecimientosActivosException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_OwnerSinEstablecimientosActivos_Anonimiza")
    void autoeliminar_OwnerSinEstablecimientosActivos_Anonimiza() {
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L)).thenReturn(List.of());
        when(reservaRepository.findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(eq(1L), any(), any())).thenReturn(List.of());

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        verify(usuarioRepository).save(any(Usuario.class));
    }

    @Test
    @DisplayName("autoeliminar_ConReservasFuturasActivas_LasCancelaYPublicaUnEventoPorCadaUna")
    void autoeliminar_ConReservasFuturasActivas_LasCancelaYPublicaUnEventoPorCadaUna() {
        Reserva reservaConfirmada = Reserva.builder().id(100L).jugador(jugador).estado(EstadoReserva.CONFIRMADA).build();
        Reserva reservaPendiente = Reserva.builder().id(101L).jugador(jugador).estado(EstadoReserva.PENDIENTE_SENA).build();

        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(reservaRepository.findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(eq(1L), any(), any()))
                .thenReturn(List.of(reservaConfirmada, reservaPendiente));

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        assertEquals(EstadoReserva.CANCELADA, reservaConfirmada.getEstado());
        assertEquals(EstadoReserva.CANCELADA, reservaPendiente.getEstado());
        verify(reservaRepository).save(reservaConfirmada);
        verify(reservaRepository).save(reservaPendiente);
        verify(eventPublisher).publishEvent(new ReservaCanceladaEvent(100L, 1L));
        verify(eventPublisher).publishEvent(new ReservaCanceladaEvent(101L, 1L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EstadoReserva>> estadosCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservaRepository).findByJugadorIdAndEstadoInAndFechaHoraInicioAfter(eq(1L), estadosCaptor.capture(), any());
        assertEquals(List.of(EstadoReserva.CONFIRMADA, EstadoReserva.PENDIENTE_SENA), estadosCaptor.getValue());
    }

    @Test
    @DisplayName("autoeliminar_CuentaYaEliminada_NoRepiteNingunEfecto")
    void autoeliminar_CuentaYaEliminada_NoRepiteNingunEfecto() {
        jugador.setDeletedAt(LocalDateTime.now().minusDays(1));
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera");

        verify(usuarioRepository, never()).save(any());
        verify(auditoriaEliminacionUsuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(passwordEncoder, never()).matches(any(), any());
    }
}
