package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.DiaNoLaborableResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
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
@DisplayName("DiaNoLaborableService - Tests de excepciones puntuales al horario de atención")
class DiaNoLaborableServiceTest {

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private DiaNoLaborableService diaNoLaborableService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
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
    @DisplayName("crear_Exito")
    void crear_Exito() {
        // Arrange
        DiaNoLaborableRequest request = new DiaNoLaborableRequest(LocalDate.of(2030, 12, 25), "Navidad");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(diaNoLaborableRepository.existsByEstablecimientoIdAndFecha(establecimiento.getId(), request.fecha()))
                .thenReturn(false);
        when(diaNoLaborableRepository.save(any(DiaNoLaborable.class))).thenAnswer(invocation -> {
            DiaNoLaborable dia = invocation.getArgument(0);
            dia.setId(1L);
            return dia;
        });

        // Act
        DiaNoLaborableResponse response = assertDoesNotThrow(
                () -> diaNoLaborableService.crear(establecimiento.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals(LocalDate.of(2030, 12, 25), response.fecha());
        assertEquals("Navidad", response.motivo());
        verify(diaNoLaborableRepository).save(any(DiaNoLaborable.class));
    }

    @Test
    @DisplayName("crear_Fallo_FechaYaCargada")
    void crear_Fallo_FechaYaCargada() {
        // Arrange
        DiaNoLaborableRequest request = new DiaNoLaborableRequest(LocalDate.of(2030, 12, 25), "Navidad");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(diaNoLaborableRepository.existsByEstablecimientoIdAndFecha(establecimiento.getId(), request.fecha()))
                .thenReturn(true);

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> diaNoLaborableService.crear(establecimiento.getId(), request, dueno.getEmail())
        );
        verify(diaNoLaborableRepository, never()).save(any());
    }

    @Test
    @DisplayName("crear_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crear_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        DiaNoLaborableRequest request = new DiaNoLaborableRequest(LocalDate.of(2030, 12, 25), "Navidad");

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
                () -> diaNoLaborableService.crear(establecimiento.getId(), request, otroDueno.getEmail())
        );
        verify(diaNoLaborableRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminar_Exito")
    void eliminar_Exito() {
        // Arrange
        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.of(2030, 12, 25))
                .motivo("Navidad")
                .build();

        when(diaNoLaborableRepository.findById(diaNoLaborable.getId())).thenReturn(Optional.of(diaNoLaborable));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        assertDoesNotThrow(() -> diaNoLaborableService.eliminar(establecimiento.getId(), diaNoLaborable.getId(), dueno.getEmail()));

        // Assert
        verify(diaNoLaborableRepository).delete(diaNoLaborable);
    }

    @Test
    @DisplayName("eliminar_Fallo_NoPerteneceAlEstablecimiento")
    void eliminar_Fallo_NoPerteneceAlEstablecimiento() {
        // Arrange
        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(99L)
                .nombre("Otro")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(otroEstablecimiento)
                .fecha(LocalDate.of(2030, 12, 25))
                .motivo("Navidad")
                .build();

        when(diaNoLaborableRepository.findById(diaNoLaborable.getId())).thenReturn(Optional.of(diaNoLaborable));

        // Act & Assert: se pide eliminar pasando el establecimiento equivocado
        assertThrows(
                IllegalArgumentException.class,
                () -> diaNoLaborableService.eliminar(establecimiento.getId(), diaNoLaborable.getId(), dueno.getEmail())
        );
        verify(diaNoLaborableRepository, never()).delete(any());
    }

    @Test
    @DisplayName("listar_Exito")
    void listar_Exito() {
        // Arrange
        DiaNoLaborable diaNoLaborable = DiaNoLaborable.builder()
                .id(1L)
                .establecimiento(establecimiento)
                .fecha(LocalDate.of(2030, 12, 25))
                .motivo("Navidad")
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(diaNoLaborableRepository.findByEstablecimientoIdOrderByFechaAsc(establecimiento.getId()))
                .thenReturn(List.of(diaNoLaborable));

        // Act
        List<DiaNoLaborableResponse> response = assertDoesNotThrow(
                () -> diaNoLaborableService.listar(establecimiento.getId(), dueno.getEmail()));

        // Assert
        assertEquals(1, response.size());
        assertEquals(LocalDate.of(2030, 12, 25), response.get(0).fecha());
    }
}
