package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstablecimientoEstadoServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private EstablecimientoEstadoService establecimientoEstadoService;

    private Usuario dueno() {
        return Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER).build();
    }

    private Establecimiento establecimiento(boolean activo, EstadoVerificacion estado) {
        return Establecimiento.builder()
                .id(10L)
                .nombre("Complejo Test")
                .isActive(activo)
                .estadoVerificacion(estado)
                .dueno(dueno())
                .build();
    }

    @Test
    @DisplayName("cambiarEstado_Deshabilitar_ApagaIsActiveNoTocaEstadoVerificacionEInvalidaLaCache")
    void cambiarEstado_Deshabilitar_ApagaIsActiveNoTocaEstadoVerificacionEInvalidaLaCache() {
        Establecimiento establecimiento = establecimiento(true, EstadoVerificacion.VERIFICADO);
        Usuario dueno = dueno();
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasConfirmadas(eq(10L), any())).thenReturn(3L);

        CambiarEstadoEstablecimientoResponse response = establecimientoEstadoService.cambiarEstado(
                10L, new CambiarEstadoEstablecimientoRequest(false), "dueno@test.com");

        assertFalse(response.isActive());
        assertEquals(EstadoVerificacion.VERIFICADO, response.estadoVerificacion());
        assertEquals(3L, response.reservasFuturasConfirmadas());
        assertFalse(establecimiento.getIsActive());
        assertEquals(EstadoVerificacion.VERIFICADO, establecimiento.getEstadoVerificacion());

        verify(complejoDetalleCache).invalidarPorEstablecimientoId(10L);
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.DESHABILITAR_ESTABLECIMIENTO), eq(10L), any());
    }

    @Test
    @DisplayName("cambiarEstado_Habilitar_PrendeIsActiveNoTocaEstadoVerificacion")
    void cambiarEstado_Habilitar_PrendeIsActiveNoTocaEstadoVerificacion() {
        Establecimiento establecimiento = establecimiento(false, EstadoVerificacion.VERIFICADO);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "dueno@test.com")).thenReturn(dueno());
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasConfirmadas(anyLong(), any())).thenReturn(0L);

        CambiarEstadoEstablecimientoResponse response = establecimientoEstadoService.cambiarEstado(
                10L, new CambiarEstadoEstablecimientoRequest(true), "dueno@test.com");

        assertEquals(Boolean.TRUE, response.isActive());
        // Rehabilitar no vuelve a la cola de revisión: sigue VERIFICADO, no PENDIENTE ni
        // EN_REVISION -- son dos ejes independientes (ver EstablecimientoOperativoGuard).
        assertEquals(EstadoVerificacion.VERIFICADO, response.estadoVerificacion());
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                any(), any(), eq(AccionAuditoria.HABILITAR_ESTABLECIMIENTO), any(), any());
    }

    @Test
    @DisplayName("cambiarEstado_NoEsElDueno_LanzaAccessDeniedExceptionYNoGuarda")
    void cambiarEstado_NoEsElDueno_LanzaAccessDeniedExceptionYNoGuarda() {
        Establecimiento establecimiento = establecimiento(true, EstadoVerificacion.VERIFICADO);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class, () -> establecimientoEstadoService.cambiarEstado(
                10L, new CambiarEstadoEstablecimientoRequest(false), "otro@test.com"));

        verify(establecimientoRepository, never()).save(any());
        verify(complejoDetalleCache, never()).invalidarPorEstablecimientoId(any());
    }
}
