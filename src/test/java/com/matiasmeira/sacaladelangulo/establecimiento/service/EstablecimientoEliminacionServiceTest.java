package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EstablecimientoEliminacionService - Baja lógica de un establecimiento")
class EstablecimientoEliminacionServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EstablecimientoEliminacionService establecimientoEliminacionService;

    private Usuario dueno;
    private Establecimiento establecimientoDeshabilitado;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(2L).email("dueno@test.com").nombre("Carlos").rol(Role.OWNER).build();
        establecimientoDeshabilitado = Establecimientos.establecimientoDeshabilitado(b -> b
                .id(10L)
                .nombre("Complejo Test")
                .slug("complejo-test")
                .dueno(dueno));
    }

    /** Sin reservas futuras confirmadas: COUNT/MAX sin GROUP BY siempre da una fila, MAX null. */
    private void sinReservasFuturasConfirmadas() {
        when(reservaRepository.resumenReservasFuturasConfirmadas(eq(10L), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{0L, null}));
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_EstablecimientoHabilitado_PideDeshabilitarPrimero")
    void eliminarEstablecimiento_Fallo_EstablecimientoHabilitado_PideDeshabilitarPrimero() {
        Establecimiento habilitado = Establecimientos.establecimientoOperativo(b -> b
                .id(10L).nombre("Complejo Test").slug("complejo-test").dueno(dueno));
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(habilitado));
        when(autorizacionEmpleadoService.validarPropietario(habilitado, dueno.getEmail())).thenReturn(dueno);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(10L, dueno.getEmail()));

        assertTrue(ex.getMessage().toLowerCase().contains("deshabilit"));
        verify(establecimientoRepository, never()).save(any());
        verify(reservaRepository, never()).resumenReservasFuturasConfirmadas(anyLong(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_ReservasFuturasConfirmadas_InformaCantidadYFecha")
    void eliminarEstablecimiento_Fallo_ReservasFuturasConfirmadas_InformaCantidadYFecha() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, dueno.getEmail())).thenReturn(dueno);
        LocalDateTime fechaMasLejana = LocalDateTime.of(2030, 6, 15, 20, 0);
        when(reservaRepository.resumenReservasFuturasConfirmadas(eq(10L), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{3L, fechaMasLejana}));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(10L, dueno.getEmail()));

        assertTrue(ex.getMessage().contains("3"));
        assertTrue(ex.getMessage().contains("2030-06-15"));
        verify(establecimientoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Exito_SoloReservasCanceladasOPasadas_NoBloquea")
    void eliminarEstablecimiento_Exito_SoloReservasCanceladasOPasadas_NoBloquea() {
        // resumenReservasFuturasConfirmadas sólo cuenta CONFIRMADA futura: reservas
        // canceladas o pasadas no aparecen acá, así que el conteo da 0 igual que si no
        // hubiera ninguna reserva -- no hace falta simular las cancels/pasadas en sí, el
        // repositorio ya las excluye por su propio WHERE (ver ReservaRepository).
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, dueno.getEmail())).thenReturn(dueno);
        sinReservasFuturasConfirmadas();
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        establecimientoEliminacionService.eliminarEstablecimiento(10L, dueno.getEmail());

        assertNotNull(establecimientoDeshabilitado.getDeletedAt());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Exito_SeteaDeletedAtRenombraSlugAuditaEInvalidaCachePorElSlugOriginal")
    void eliminarEstablecimiento_Exito_SeteaDeletedAtRenombraSlugAuditaEInvalidaCachePorElSlugOriginal() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, dueno.getEmail())).thenReturn(dueno);
        sinReservasFuturasConfirmadas();
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));

        establecimientoEliminacionService.eliminarEstablecimiento(10L, dueno.getEmail());

        assertNotNull(establecimientoDeshabilitado.getDeletedAt());
        assertEquals("complejo-test-eliminado-10", establecimientoDeshabilitado.getSlug());

        // El slug ORIGINAL, no el renombrado: se invalida por el slug con el que estaba
        // cacheada la ficha ANTES de la baja.
        verify(complejoDetalleCache).invalidarPorSlug("complejo-test");
        verify(complejoDetalleCache, never()).invalidarPorSlug("complejo-test-eliminado-10");

        ArgumentCaptor<String> detalleCaptor = ArgumentCaptor.forClass(String.class);
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimientoDeshabilitado), eq(AccionAuditoria.ELIMINAR_ESTABLECIMIENTO),
                eq(10L), detalleCaptor.capture());
        // El detalle de auditoría documenta el nombre y el SLUG ORIGINAL, no el renombrado.
        assertTrue(detalleCaptor.getValue().contains("Complejo Test"));
        assertTrue(detalleCaptor.getValue().contains("complejo-test"));

        ArgumentCaptor<EstablecimientoEliminadoEvent> eventoCaptor = ArgumentCaptor.forClass(EstablecimientoEliminadoEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(dueno.getEmail(), eventoCaptor.getValue().emailDueno());
        assertEquals("Complejo Test", eventoCaptor.getValue().nombreEstablecimiento());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_EstablecimientoNoEncontrado")
    void eliminarEstablecimiento_Fallo_EstablecimientoNoEncontrado() {
        when(establecimientoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(999L, dueno.getEmail()));
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_OtroOwnerNoEsElDueno")
    void eliminarEstablecimiento_Fallo_OtroOwnerNoEsElDueno() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, otroDueno.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(10L, otroDueno.getEmail()));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_Empleado")
    void eliminarEstablecimiento_Fallo_Empleado() {
        String emailEmpleado = "empleado-uuid@empleados.interno";
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, emailEmpleado))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(10L, emailEmpleado));
        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarEstablecimiento_Fallo_Admin_ValidarPropietarioLoExcluyeAPropósito")
    void eliminarEstablecimiento_Fallo_Admin_ValidarPropietarioLoExcluyeAProposito() {
        // validarPropietario (a diferencia de validarPropietarioOAdmin) rechaza a CUALQUIERA
        // que no sea el dueño real -- ADMIN incluido. Ver AutorizacionEmpleadoService.
        String emailAdmin = "admin@test.com";
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimientoDeshabilitado));
        when(autorizacionEmpleadoService.validarPropietario(establecimientoDeshabilitado, emailAdmin))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> establecimientoEliminacionService.eliminarEstablecimiento(10L, emailAdmin));
        verify(establecimientoRepository, never()).save(any());
    }
}
