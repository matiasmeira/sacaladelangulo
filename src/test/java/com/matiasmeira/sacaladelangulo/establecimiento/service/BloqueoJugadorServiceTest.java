package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.BloqueoJugadorResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoJugador;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BloqueoJugadorService - Tests de bloqueo de jugadores por establecimiento")
class BloqueoJugadorServiceTest {

    @Mock
    private BloqueoJugadorRepository bloqueoJugadorRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private BloqueoJugadorService bloqueoJugadorService;

    private Usuario dueno;
    private Usuario jugador;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        jugador = Usuario.builder()
                .id(5L)
                .email("jugador@test.com")
                .nombre("Juan")
                .rol(Role.PLAYER)
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
    }

    @Test
    @DisplayName("crearBloqueo_Exito")
    void crearBloqueo_Exito() {
        // Arrange
        BloqueoJugadorRequest request = new BloqueoJugadorRequest(jugador.getId(), "No-show reiterado");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.findById(jugador.getId())).thenReturn(Optional.of(jugador));
        when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimiento.getId(), jugador.getId()))
                .thenReturn(false);
        when(bloqueoJugadorRepository.save(any(BloqueoJugador.class))).thenAnswer(invocation -> {
            BloqueoJugador bloqueo = invocation.getArgument(0);
            bloqueo.setId(1L);
            return bloqueo;
        });

        // Act
        BloqueoJugadorResponse response = assertDoesNotThrow(
                () -> bloqueoJugadorService.crearBloqueo(establecimiento.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals(jugador.getId(), response.jugadorId());
        assertEquals("No-show reiterado", response.motivo());
        verify(bloqueoJugadorRepository).save(any(BloqueoJugador.class));
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_JugadorYaBloqueado")
    void crearBloqueo_Fallo_JugadorYaBloqueado() {
        // Arrange
        BloqueoJugadorRequest request = new BloqueoJugadorRequest(jugador.getId(), "No-show reiterado");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.findById(jugador.getId())).thenReturn(Optional.of(jugador));
        when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(establecimiento.getId(), jugador.getId()))
                .thenReturn(true);

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> bloqueoJugadorService.crearBloqueo(establecimiento.getId(), request, dueno.getEmail())
        );
        verify(bloqueoJugadorRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_UsuarioIndicadoNoEsRolPlayer")
    void crearBloqueo_Fallo_UsuarioIndicadoNoEsRolPlayer() {
        // Arrange
        BloqueoJugadorRequest request = new BloqueoJugadorRequest(dueno.getId(), "Motivo cualquiera");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.findById(dueno.getId())).thenReturn(Optional.of(dueno));

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> bloqueoJugadorService.crearBloqueo(establecimiento.getId(), request, dueno.getEmail())
        );
        verify(bloqueoJugadorRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearBloqueo_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearBloqueo_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        BloqueoJugadorRequest request = new BloqueoJugadorRequest(jugador.getId(), "Motivo cualquiera");

        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .rol(Role.OWNER)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> bloqueoJugadorService.crearBloqueo(establecimiento.getId(), request, otroDueno.getEmail())
        );
        verify(bloqueoJugadorRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarBloqueo_Exito")
    void eliminarBloqueo_Exito() {
        // Arrange
        BloqueoJugador bloqueo = BloqueoJugador.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .jugador(jugador)
                .motivo("No-show reiterado")
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(bloqueoJugadorRepository.findByEstablecimientoIdAndJugadorId(establecimiento.getId(), jugador.getId()))
                .thenReturn(Optional.of(bloqueo));

        // Act
        assertDoesNotThrow(() -> bloqueoJugadorService.eliminarBloqueo(establecimiento.getId(), jugador.getId(), dueno.getEmail()));

        // Assert
        verify(bloqueoJugadorRepository).delete(bloqueo);
    }

    @Test
    @DisplayName("eliminarBloqueo_Fallo_JugadorNoEstaBloqueado")
    void eliminarBloqueo_Fallo_JugadorNoEstaBloqueado() {
        // Arrange
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(bloqueoJugadorRepository.findByEstablecimientoIdAndJugadorId(establecimiento.getId(), jugador.getId()))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
                EntityNotFoundException.class,
                () -> bloqueoJugadorService.eliminarBloqueo(establecimiento.getId(), jugador.getId(), dueno.getEmail())
        );
        verify(bloqueoJugadorRepository, never()).delete(any());
    }

    @Test
    @DisplayName("listarBloqueados_Exito")
    void listarBloqueados_Exito() {
        // Arrange
        BloqueoJugador bloqueo = BloqueoJugador.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .jugador(jugador)
                .motivo("No-show reiterado")
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(bloqueoJugadorRepository.findByEstablecimientoIdOrderByFechaBloqueoDesc(establecimiento.getId()))
                .thenReturn(List.of(bloqueo));

        // Act
        List<BloqueoJugadorResponse> response = assertDoesNotThrow(
                () -> bloqueoJugadorService.listarBloqueados(establecimiento.getId(), dueno.getEmail()));

        // Assert
        assertEquals(1, response.size());
        assertEquals(jugador.getId(), response.get(0).jugadorId());
    }
}
