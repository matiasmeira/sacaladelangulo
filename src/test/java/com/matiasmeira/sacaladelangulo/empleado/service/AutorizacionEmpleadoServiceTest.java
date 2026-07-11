package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutorizacionEmpleadoService - Tests de autorización dueño/admin/empleado")
class AutorizacionEmpleadoServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @InjectMocks
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

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
    @DisplayName("validarAccion_Exito_EsDuenoReal")
    void validarAccion_Exito_EsDuenoReal() {
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        Usuario resultado = autorizacionEmpleadoService.validarAccion(
                establecimiento, dueno.getEmail(), PermisoEmpleado.CANCELAR_RESERVA);

        assertEquals(dueno.getId(), resultado.getId());
    }

    @Test
    @DisplayName("validarAccion_Exito_EsAdmin")
    void validarAccion_Exito_EsAdmin() {
        Usuario admin = Usuario.builder().id(99L).email("admin@test.com").rol(Role.ADMIN).build();
        when(usuarioRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));

        Usuario resultado = autorizacionEmpleadoService.validarAccion(
                establecimiento, admin.getEmail(), PermisoEmpleado.CANCELAR_RESERVA);

        assertEquals(admin.getId(), resultado.getId());
    }

    @Test
    @DisplayName("validarAccion_Exito_EsEmpleadoConElPermisoHabilitado")
    void validarAccion_Exito_EsEmpleadoConElPermisoHabilitado() {
        Usuario empleado = Usuario.builder()
                .id(5L)
                .email("empleado-uuid@empleados.interno")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.CANCELAR_RESERVA))
                .build();
        when(usuarioRepository.findByEmail(empleado.getEmail())).thenReturn(Optional.of(empleado));

        Usuario resultado = autorizacionEmpleadoService.validarAccion(
                establecimiento, empleado.getEmail(), PermisoEmpleado.CANCELAR_RESERVA);

        assertEquals(empleado.getId(), resultado.getId());
    }

    @Test
    @DisplayName("validarAccion_Fallo_EmpleadoSinElPermisoHabilitado")
    void validarAccion_Fallo_EmpleadoSinElPermisoHabilitado() {
        Usuario empleado = Usuario.builder()
                .id(5L)
                .email("empleado-uuid@empleados.interno")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET))
                .build();
        when(usuarioRepository.findByEmail(empleado.getEmail())).thenReturn(Optional.of(empleado));

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> autorizacionEmpleadoService.validarAccion(establecimiento, empleado.getEmail(), PermisoEmpleado.CANCELAR_RESERVA)
        );
    }

    @Test
    @DisplayName("validarAccion_Fallo_EmpleadoDeOtroEstablecimiento")
    void validarAccion_Fallo_EmpleadoDeOtroEstablecimiento() {
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
                .id(5L)
                .email("empleado-uuid@empleados.interno")
                .rol(Role.EMPLOYEE)
                .establecimiento(otroEstablecimiento)
                .permisos(Set.of(PermisoEmpleado.CANCELAR_RESERVA))
                .build();
        when(usuarioRepository.findByEmail(empleadoDeOtroEstablecimiento.getEmail())).thenReturn(Optional.of(empleadoDeOtroEstablecimiento));

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> autorizacionEmpleadoService.validarAccion(establecimiento, empleadoDeOtroEstablecimiento.getEmail(), PermisoEmpleado.CANCELAR_RESERVA)
        );
    }

    @Test
    @DisplayName("validarAccion_Fallo_OtroDueñoNoRelacionadoConElEstablecimiento")
    void validarAccion_Fallo_OtroDuenoNoRelacionadoConElEstablecimiento() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> autorizacionEmpleadoService.validarAccion(establecimiento, otroDueno.getEmail(), PermisoEmpleado.CANCELAR_RESERVA)
        );
    }

    @Test
    @DisplayName("validarAccion_Fallo_UsuarioNoEncontrado")
    void validarAccion_Fallo_UsuarioNoEncontrado() {
        when(usuarioRepository.findByEmail("fantasma@test.com")).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> autorizacionEmpleadoService.validarAccion(establecimiento, "fantasma@test.com", PermisoEmpleado.CANCELAR_RESERVA)
        );
    }

    @Test
    @DisplayName("tienePermiso_True_EmpleadoDelEstablecimientoConElPermiso")
    void tienePermiso_True_EmpleadoDelEstablecimientoConElPermiso() {
        Usuario empleado = Usuario.builder()
                .id(5L)
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.FINALIZAR_RESERVA))
                .build();

        assertTrue(autorizacionEmpleadoService.tienePermiso(empleado, establecimiento, PermisoEmpleado.FINALIZAR_RESERVA));
    }

    @Test
    @DisplayName("tienePermiso_False_UsuarioNoEsEmpleado")
    void tienePermiso_False_UsuarioNoEsEmpleado() {
        assertFalse(autorizacionEmpleadoService.tienePermiso(dueno, establecimiento, PermisoEmpleado.FINALIZAR_RESERVA));
    }

    @Test
    @DisplayName("tienePermiso_False_EmpleadoSinEsePermiso")
    void tienePermiso_False_EmpleadoSinEsePermiso() {
        Usuario empleado = Usuario.builder()
                .id(5L)
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of())
                .build();

        assertFalse(autorizacionEmpleadoService.tienePermiso(empleado, establecimiento, PermisoEmpleado.FINALIZAR_RESERVA));
    }
}
