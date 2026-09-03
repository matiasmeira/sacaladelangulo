package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DegradacionPlanService - Degradación TRIAL -> FREE por usuario")
class DegradacionPlanServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DegradacionPlanService service;

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioTrialVencido_PasaAFreeAuditaYPublicaEvento")
    void degradarPorVencimiento_UsuarioTrialVencido_PasaAFreeAuditaYPublicaEvento() {
        Usuario usuario = usuarioDePrueba(1L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(1L);

        assertEquals(PlanSuscripcion.FREE, usuario.getPlanSuscripcion());
        verify(usuarioRepository).save(usuario);

        ArgumentCaptor<AuditoriaDegradacionPlan> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaDegradacionPlan.class);
        verify(auditoriaDegradacionPlanRepository).save(auditoriaCaptor.capture());
        assertEquals(usuario, auditoriaCaptor.getValue().getUsuario());

        ArgumentCaptor<PruebaVencidaEvent> eventoCaptor = ArgumentCaptor.forClass(PruebaVencidaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(1L, eventoCaptor.getValue().usuarioId());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioYaEnFree_NoHaceNada")
    void degradarPorVencimiento_UsuarioYaEnFree_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(2L, PlanSuscripcion.FREE, null);
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(2L);

        verify(usuarioRepository, never()).save(any());
        verify(auditoriaDegradacionPlanRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioYaEnPremium_NoHaceNada")
    void degradarPorVencimiento_UsuarioYaEnPremium_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(3L, PlanSuscripcion.PREMIUM, null);
        when(usuarioRepository.findById(3L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(3L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_CuentaEliminada_NoHaceNada")
    void degradarPorVencimiento_CuentaEliminada_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(4L, PlanSuscripcion.TRIAL, LocalDateTime.now());
        when(usuarioRepository.findById(4L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(4L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioNoEncontrado_NoHaceNada")
    void degradarPorVencimiento_UsuarioNoEncontrado_NoHaceNada() {
        when(usuarioRepository.findById(5L)).thenReturn(Optional.empty());

        service.degradarPorVencimiento(5L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_DosVecesSeguidas_LaSegundaEsNoOp")
    void degradarPorVencimiento_DosVecesSeguidas_LaSegundaEsNoOp() {
        Usuario usuario = usuarioDePrueba(6L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(6L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(6L);
        service.degradarPorVencimiento(6L);

        verify(usuarioRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any(PruebaVencidaEvent.class));
    }

    private Usuario usuarioDePrueba(Long id, PlanSuscripcion plan, LocalDateTime deletedAt) {
        Usuario usuario = Usuario.builder()
                .email("usuario" + id + "@test.com")
                .password("hash")
                .nombre("Usuario " + id)
                .planSuscripcion(plan)
                .fechaFinPrueba(LocalDateTime.now().minusDays(1))
                .deletedAt(deletedAt)
                .build();
        usuario.setId(id);
        return usuario;
    }
}
