package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.empleado.dto.ActualizarPermisosRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.CambiarPinRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoMapper;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoNombreResponse;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoRequest;
import com.matiasmeira.sacaladelangulo.empleado.dto.EmpleadoResponse;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmpleadoService - Tests de gestión de empleados")
class EmpleadoServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    private EmpleadoService empleadoService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        empleadoService = new EmpleadoService(usuarioRepository, establecimientoRepository, passwordEncoder, new EmpleadoMapper(), registroAuditoriaService);

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
    @DisplayName("crearEmpleado_Exito")
    void crearEmpleado_Exito() {
        // Arrange
        EmpleadoRequest request = new EmpleadoRequest("Juan", "1234", Set.of(PermisoEmpleado.CANCELAR_RESERVA));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.existsByEstablecimientoIdAndNombreAndRol(establecimiento.getId(), "Juan", Role.EMPLOYEE)).thenReturn(false);
        when(passwordEncoder.encode("1234")).thenReturn("hash-1234");
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> {
            Usuario empleado = invocation.getArgument(0);
            empleado.setId(50L);
            return empleado;
        });

        // Act
        EmpleadoResponse response = assertDoesNotThrow(
                () -> empleadoService.crearEmpleado(establecimiento.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals("Juan", response.nombre());
        assertEquals(Set.of(PermisoEmpleado.CANCELAR_RESERVA), response.permisos());
        assertEquals(establecimiento.getId(), response.establecimientoId());
        verify(usuarioRepository).save(argThatEmpleadoTieneRolYPin());
        verify(registroAuditoriaService).registrarAdministrativa(
                eq(dueno), any(Usuario.class), eq(AccionAuditoria.CREAR_EMPLEADO), any());
    }

    private Usuario argThatEmpleadoTieneRolYPin() {
        return org.mockito.ArgumentMatchers.argThat(u ->
                u.getRol() == Role.EMPLOYEE && "hash-1234".equals(u.getPassword()) && u.getEmail() != null);
    }

    @Test
    @DisplayName("crearEmpleado_Fallo_NombreDuplicadoEnElMismoEstablecimiento")
    void crearEmpleado_Fallo_NombreDuplicadoEnElMismoEstablecimiento() {
        // Arrange
        EmpleadoRequest request = new EmpleadoRequest("Juan", "1234", null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.existsByEstablecimientoIdAndNombreAndRol(establecimiento.getId(), "Juan", Role.EMPLOYEE)).thenReturn(true);

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> empleadoService.crearEmpleado(establecimiento.getId(), request, dueno.getEmail())
        );
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearEmpleado_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearEmpleado_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        EmpleadoRequest request = new EmpleadoRequest("Juan", "1234", null);

        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> empleadoService.crearEmpleado(establecimiento.getId(), request, otroDueno.getEmail())
        );
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Exito")
    void listarPorEstablecimiento_Exito() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(50L)
                .email("empleado-uuid@empleados.interno")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.CANCELAR_RESERVA))
                .isActive(true)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.findByEstablecimientoIdAndRol(establecimiento.getId(), Role.EMPLOYEE)).thenReturn(List.of(empleado));

        // Act
        List<EmpleadoResponse> response = assertDoesNotThrow(
                () -> empleadoService.listarPorEstablecimiento(establecimiento.getId(), dueno.getEmail()));

        // Assert
        assertEquals(1, response.size());
        assertEquals("Juan", response.get(0).nombre());
    }

    @Test
    @DisplayName("listarNombresActivos_Exito_NoRequiereAutenticacion")
    void listarNombresActivos_Exito_NoRequiereAutenticacion() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(50L)
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .isActive(true)
                .build();

        when(usuarioRepository.findByEstablecimientoIdAndRolAndIsActiveTrue(establecimiento.getId(), Role.EMPLOYEE))
                .thenReturn(List.of(empleado));

        // Act
        List<EmpleadoNombreResponse> response = empleadoService.listarNombresActivos(establecimiento.getId());

        // Assert
        assertEquals(1, response.size());
        assertEquals("Juan", response.get(0).nombre());
        assertEquals(50L, response.get(0).id());
    }

    @Test
    @DisplayName("actualizarPermisos_Exito")
    void actualizarPermisos_Exito() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(50L)
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.CANCELAR_RESERVA))
                .isActive(true)
                .build();

        ActualizarPermisosRequest request = new ActualizarPermisosRequest(
                Set.of(PermisoEmpleado.CANCELAR_RESERVA, PermisoEmpleado.REGISTRAR_VENTA_BUFFET));

        when(usuarioRepository.findById(empleado.getId())).thenReturn(Optional.of(empleado));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        EmpleadoResponse response = assertDoesNotThrow(
                () -> empleadoService.actualizarPermisos(establecimiento.getId(), empleado.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals(2, response.permisos().size());
        verify(registroAuditoriaService).registrarAdministrativa(
                eq(dueno), eq(empleado), eq(AccionAuditoria.ACTUALIZAR_PERMISOS_EMPLEADO), any());
    }

    @Test
    @DisplayName("actualizarPermisos_Fallo_EmpleadoNoPerteneceAlEstablecimiento")
    void actualizarPermisos_Fallo_EmpleadoNoPerteneceAlEstablecimiento() {
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

        Usuario empleadoDeOtroEstablecimiento = Usuario.builder()
                .id(50L)
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(otroEstablecimiento)
                .isActive(true)
                .build();

        ActualizarPermisosRequest request = new ActualizarPermisosRequest(Set.of(PermisoEmpleado.CANCELAR_RESERVA));

        when(usuarioRepository.findById(empleadoDeOtroEstablecimiento.getId())).thenReturn(Optional.of(empleadoDeOtroEstablecimiento));

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> empleadoService.actualizarPermisos(establecimiento.getId(), empleadoDeOtroEstablecimiento.getId(), request, dueno.getEmail())
        );
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("cambiarPin_Exito")
    void cambiarPin_Exito() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(50L)
                .nombre("Juan")
                .password("hash-vieja")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .isActive(true)
                .build();

        CambiarPinRequest request = new CambiarPinRequest("5678");

        when(usuarioRepository.findById(empleado.getId())).thenReturn(Optional.of(empleado));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(passwordEncoder.encode("5678")).thenReturn("hash-5678");
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        assertDoesNotThrow(() -> empleadoService.cambiarPin(establecimiento.getId(), empleado.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals("hash-5678", empleado.getPassword());
        verify(registroAuditoriaService).registrarAdministrativa(
                eq(dueno), eq(empleado), eq(AccionAuditoria.CAMBIAR_PIN_EMPLEADO), any());
    }

    @Test
    @DisplayName("desactivarEmpleado_Exito")
    void desactivarEmpleado_Exito() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(50L)
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .isActive(true)
                .build();

        when(usuarioRepository.findById(empleado.getId())).thenReturn(Optional.of(empleado));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        assertDoesNotThrow(() -> empleadoService.desactivarEmpleado(establecimiento.getId(), empleado.getId(), dueno.getEmail()));

        // Assert
        assertEquals(false, empleado.getIsActive());
        verify(registroAuditoriaService).registrarAdministrativa(
                eq(dueno), eq(empleado), eq(AccionAuditoria.DESACTIVAR_EMPLEADO), any());
    }
}
