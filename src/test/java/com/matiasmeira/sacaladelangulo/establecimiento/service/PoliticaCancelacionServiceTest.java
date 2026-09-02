package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoliticaCancelacionService")
class PoliticaCancelacionServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock
    private RegistroAuditoriaService registroAuditoriaService;
    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private PoliticaCancelacionService politicaCancelacionService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Complejo Test")
                .horasCancelacionAntesPartido(24)
                .minutosGraciaCancelacion(30)
                .dueno(dueno)
                .build();
    }

    @Test
    @DisplayName("obtenerPoliticaCancelacion_Exito_DevuelveValoresActualesConReservasFuturasAfectadasEnNull")
    void obtenerPoliticaCancelacion_Exito_DevuelveValoresActualesConReservasFuturasAfectadasEnNull() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        PoliticaCancelacionResponse response = politicaCancelacionService.obtenerPoliticaCancelacion(10L, dueno.getEmail());

        assertEquals(24, response.horasCancelacionAntesPartido());
        assertEquals(30, response.minutosGraciaCancelacion());
        assertNull(response.reservasFuturasAfectadas());
    }

    @Test
    @DisplayName("obtenerPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException")
    void obtenerPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> politicaCancelacionService.obtenerPoliticaCancelacion(99L, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_SoloHoras_ActualizaSoloEseCampo")
    void actualizarPoliticaCancelacion_SoloHoras_ActualizaSoloEseCampo() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(12, null);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(12, response.horasCancelacionAntesPartido());
        assertEquals(30, response.minutosGraciaCancelacion());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_SoloMinutos_ActualizaSoloEseCampo")
    void actualizarPoliticaCancelacion_SoloMinutos_ActualizaSoloEseCampo() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(null, 45);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(24, response.horasCancelacionAntesPartido());
        assertEquals(45, response.minutosGraciaCancelacion());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_AmbosCampos_ActualizaLosDosYDevuelveReservasAfectadas")
    void actualizarPoliticaCancelacion_AmbosCampos_ActualizaLosDosYDevuelveReservasAfectadas() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(3L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(6, 15);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(6, response.horasCancelacionAntesPartido());
        assertEquals(15, response.minutosGraciaCancelacion());
        assertEquals(3, response.reservasFuturasAfectadas());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_AmbosNull_LanzaIllegalArgumentException")
    void actualizarPoliticaCancelacion_AmbosNull_LanzaIllegalArgumentException() {
        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(null, null);

        assertThrows(IllegalArgumentException.class,
                () -> politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException")
    void actualizarPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findById(99L)).thenReturn(Optional.empty());
        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(12, null);

        assertThrows(EntityNotFoundException.class,
                () -> politicaCancelacionService.actualizarPoliticaCancelacion(99L, request, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_Exito_RegistraAuditoria")
    void actualizarPoliticaCancelacion_Exito_RegistraAuditoria() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(6, 15);

        politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION), eq(10L), any());
    }
}
