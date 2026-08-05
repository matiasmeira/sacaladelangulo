package com.matiasmeira.sacaladelangulo.empleado.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.dto.RegistroAuditoriaResponse;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.model.RegistroAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistroAuditoriaService - Tests de registro y consulta de auditoría")
class RegistroAuditoriaServiceTest {

    @Mock
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @InjectMocks
    private RegistroAuditoriaService registroAuditoriaService;

    private Usuario dueno;
    private Establecimiento establecimiento;
    private Usuario empleado;

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

        empleado = Usuario.builder()
                .id(5L)
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .build();
    }

    @Test
    @DisplayName("registrar_Exito_GuardaElRegistroConLosDatosCorrectos")
    void registrar_Exito_GuardaElRegistroConLosDatosCorrectos() {
        // Act
        registroAuditoriaService.registrar(empleado, AccionAuditoria.CANCELAR_RESERVA, 100L, true, "Reserva cancelada");

        // Assert
        ArgumentCaptor<RegistroAuditoria> captor = ArgumentCaptor.forClass(RegistroAuditoria.class);
        verify(registroAuditoriaRepository).save(captor.capture());

        RegistroAuditoria guardado = captor.getValue();
        assertEquals(empleado.getId(), guardado.getEmpleado().getId());
        assertEquals(establecimiento.getId(), guardado.getEstablecimiento().getId());
        assertEquals(AccionAuditoria.CANCELAR_RESERVA, guardado.getAccion());
        assertEquals(100L, guardado.getEntidadAfectadaId());
        assertEquals(true, guardado.getExitoso());
        assertEquals("Reserva cancelada", guardado.getDetalle());
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Exito_EsDuenoReal")
    void listarPorEstablecimiento_Exito_EsDuenoReal() {
        // Arrange
        RegistroAuditoria registro = RegistroAuditoria.builder()
                .id(1L)
                .empleado(empleado)
                .establecimiento(establecimiento)
                .accion(AccionAuditoria.CANCELAR_RESERVA)
                .entidadAfectadaId(100L)
                .exitoso(true)
                .detalle("Reserva cancelada")
                .fechaHora(LocalDateTime.now())
                .build();
        Pageable pageable = PageRequest.of(0, 20);
        Page<RegistroAuditoria> pagina = new PageImpl<>(List.of(registro), pageable, 1);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(registroAuditoriaRepository.findByEstablecimientoIdOrderByFechaHoraDesc(establecimiento.getId(), pageable))
                .thenReturn(pagina);

        // Act
        Page<RegistroAuditoriaResponse> resultado = registroAuditoriaService.listarPorEstablecimiento(
                establecimiento.getId(), pageable, dueno.getEmail());

        // Assert
        assertEquals(1, resultado.getTotalElements());
        assertEquals("Juan", resultado.getContent().get(0).empleadoNombre());
        assertEquals("CANCELAR_RESERVA", resultado.getContent().get(0).accion());
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Exito_EsAdmin")
    void listarPorEstablecimiento_Exito_EsAdmin() {
        // Arrange
        Usuario admin = Usuario.builder().id(99L).email("admin@test.com").rol(Role.ADMIN).build();
        Pageable pageable = PageRequest.of(0, 20);
        Page<RegistroAuditoria> paginaVacia = new PageImpl<>(List.of(), pageable, 0);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, admin.getEmail())).thenReturn(admin);
        when(registroAuditoriaRepository.findByEstablecimientoIdOrderByFechaHoraDesc(establecimiento.getId(), pageable))
                .thenReturn(paginaVacia);

        // Act
        Page<RegistroAuditoriaResponse> resultado = registroAuditoriaService.listarPorEstablecimiento(
                establecimiento.getId(), pageable, admin.getEmail());

        // Assert
        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Fallo_UsuarioNoEsDuenoNiAdmin")
    void listarPorEstablecimiento_Fallo_UsuarioNoEsDuenoNiAdmin() {
        // Arrange
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();
        Pageable pageable = PageRequest.of(0, 20);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> registroAuditoriaService.listarPorEstablecimiento(establecimiento.getId(), pageable, otroDueno.getEmail())
        );
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Fallo_EstablecimientoNoEncontrado")
    void listarPorEstablecimiento_Fallo_EstablecimientoNoEncontrado() {
        // Arrange
        Pageable pageable = PageRequest.of(0, 20);
        when(establecimientoRepository.findById(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
                EntityNotFoundException.class,
                () -> registroAuditoriaService.listarPorEstablecimiento(999L, pageable, dueno.getEmail())
        );
    }
}
