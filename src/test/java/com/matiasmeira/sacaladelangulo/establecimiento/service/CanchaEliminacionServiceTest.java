package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

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
@DisplayName("CanchaEliminacionService - Baja lógica de una cancha")
class CanchaEliminacionServiceTest {

    @Mock
    private CanchaRepository canchaRepository;

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

    @InjectMocks
    private CanchaEliminacionService canchaEliminacionService;

    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha canchaDesactivada;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(2L).email("dueno@test.com").nombre("Carlos").rol(Role.OWNER).build();
        establecimiento = Establecimientos.establecimientoOperativo(b -> b.id(10L).dueno(dueno));
        canchaDesactivada = Canchas.canchaDesactivada(establecimiento, b -> b.id(100L).nombre("Cancha 1"));
    }

    /** Sin reservas futuras confirmadas: COUNT/MAX sin GROUP BY siempre da una fila, MAX null. */
    private void sinReservasFuturasConfirmadas() {
        when(reservaRepository.resumenReservasFuturasConfirmadasPorCancha(eq(100L), any()))
                .thenReturn(Collections.singletonList(new Object[]{0L, null}));
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_CanchaActiva_PideDesactivarPrimero")
    void eliminarCancha_Fallo_CanchaActiva_PideDesactivarPrimero() {
        Cancha canchaActiva = Canchas.canchaActiva(establecimiento, b -> b.id(100L).nombre("Cancha 1"));
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaActiva));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, dueno.getEmail()));

        assertTrue(ex.getMessage().toLowerCase().contains("desactiv"));
        verify(canchaRepository, never()).save(any());
        verify(reservaRepository, never()).resumenReservasFuturasConfirmadasPorCancha(anyLong(), any());
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_ReservasFuturasConfirmadas_InformaCantidadYFecha")
    void eliminarCancha_Fallo_ReservasFuturasConfirmadas_InformaCantidadYFecha() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaDesactivada));
        LocalDateTime fechaMasLejana = LocalDateTime.of(2030, 6, 15, 20, 0);
        when(reservaRepository.resumenReservasFuturasConfirmadasPorCancha(eq(100L), any()))
                .thenReturn(Collections.singletonList(new Object[]{3L, fechaMasLejana}));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, dueno.getEmail()));

        assertTrue(ex.getMessage().contains("3"));
        assertTrue(ex.getMessage().contains("2030-06-15"));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarCancha_Exito_SoloReservasCanceladasOPasadas_NoBloquea")
    void eliminarCancha_Exito_SoloReservasCanceladasOPasadas_NoBloquea() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaDesactivada));
        sinReservasFuturasConfirmadas();
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(inv -> inv.getArgument(0));

        canchaEliminacionService.eliminarCancha(10L, 100L, dueno.getEmail());

        assertNotNull(canchaDesactivada.getDeletedAt());
    }

    @Test
    @DisplayName("eliminarCancha_Exito_SeteaDeletedAtAuditaEInvalidaCache")
    void eliminarCancha_Exito_SeteaDeletedAtAuditaEInvalidaCache() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaDesactivada));
        sinReservasFuturasConfirmadas();
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(inv -> inv.getArgument(0));

        canchaEliminacionService.eliminarCancha(10L, 100L, dueno.getEmail());

        assertNotNull(canchaDesactivada.getDeletedAt());

        ArgumentCaptor<String> detalleCaptor = ArgumentCaptor.forClass(String.class);
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.ELIMINAR_CANCHA),
                eq(100L), detalleCaptor.capture());
        assertTrue(detalleCaptor.getValue().contains("Cancha 1"));

        verify(complejoDetalleCache).invalidarPorEstablecimientoId(10L);
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_CanchaNoEncontrada")
    void eliminarCancha_Fallo_CanchaNoEncontrada() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 999L, dueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_EstablecimientoNoEncontrado")
    void eliminarCancha_Fallo_EstablecimientoNoEncontrado() {
        when(establecimientoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> canchaEliminacionService.eliminarCancha(999L, 100L, dueno.getEmail()));
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento")
    void eliminarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento() {
        Establecimiento otroEstablecimiento = Establecimientos.establecimientoOperativo(b -> b.id(20L).dueno(dueno));
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, dueno.getEmail())).thenReturn(dueno);
        Cancha canchaDeOtroEstablecimiento = Canchas.canchaDesactivada(otroEstablecimiento, b -> b.id(100L));
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaDeOtroEstablecimiento));

        assertThrows(IllegalArgumentException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, dueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_OtroOwnerNoEsElDueno")
    void eliminarCancha_Fallo_OtroOwnerNoEsElDueno() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, otroDueno.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, otroDueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_Empleado")
    void eliminarCancha_Fallo_Empleado() {
        String emailEmpleado = "empleado-uuid@empleados.interno";
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, emailEmpleado))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, emailEmpleado));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarCancha_Fallo_Admin_ValidarPropietarioLoExcluyeAPropósito")
    void eliminarCancha_Fallo_Admin_ValidarPropietarioLoExcluyeAProposito() {
        // validarPropietario (a diferencia de validarPropietarioOAdmin) rechaza a CUALQUIERA
        // que no sea el dueño real -- ADMIN incluido. Ver AutorizacionEmpleadoService.
        String emailAdmin = "admin@test.com";
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietario(establecimiento, emailAdmin))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> canchaEliminacionService.eliminarCancha(10L, 100L, emailAdmin));
        verify(canchaRepository, never()).save(any());
    }
}
