package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private CanchaRepository canchaRepository;

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
        verify(establecimientoRepository, never()).findByDuenoIdAndDeletedAtIsNull(any());
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

    @Test
    @DisplayName("degradarPorVencimiento_EstablecimientosEnLosCuatroEstadosDeVerificacion_QuedanConRequiereSenaTrue")
    void degradarPorVencimiento_EstablecimientosEnLosCuatroEstadosDeVerificacion_QuedanConRequiereSenaTrue() {
        Usuario usuario = usuarioDePrueba(10L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(usuario));

        Establecimiento pendiente = establecimientoDePrueba(101L, EstadoVerificacion.PENDIENTE);
        Establecimiento enRevision = establecimientoDePrueba(102L, EstadoVerificacion.EN_REVISION);
        Establecimiento verificado = establecimientoDePrueba(103L, EstadoVerificacion.VERIFICADO);
        Establecimiento rechazado = establecimientoDePrueba(104L, EstadoVerificacion.RECHAZADO);
        List<Establecimiento> establecimientos = List.of(pendiente, enRevision, verificado, rechazado);
        when(establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(10L)).thenReturn(establecimientos);

        service.degradarPorVencimiento(10L);

        ArgumentCaptor<List<Establecimiento>> captor = ArgumentCaptor.forClass(List.class);
        verify(establecimientoRepository).saveAll(captor.capture());
        assertEquals(4, captor.getValue().size());
        assertTrue(pendiente.getRequiereSena());
        assertTrue(enRevision.getRequiereSena());
        assertTrue(verificado.getRequiereSena());
        assertTrue(rechazado.getRequiereSena());
    }

    @Test
    @DisplayName("degradarPorVencimiento_CanchasPorDebajoDelMinimo_SubenA500YLasDemasNoSeTocan")
    void degradarPorVencimiento_CanchasPorDebajoDelMinimo_SubenA500YLasDemasNoSeTocan() {
        Usuario usuario = usuarioDePrueba(11L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(11L)).thenReturn(Optional.of(usuario));

        Establecimiento establecimiento = establecimientoDePrueba(200L, EstadoVerificacion.VERIFICADO);
        when(establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(11L)).thenReturn(List.of(establecimiento));

        Cancha canchaCero = canchaDePrueba(301L, BigDecimal.ZERO, true);
        Cancha canchaDoscientos = canchaDePrueba(302L, BigDecimal.valueOf(200), true);
        Cancha canchaQuinientos = canchaDePrueba(303L, BigDecimal.valueOf(500), true);
        Cancha canchaOchocientos = canchaDePrueba(304L, BigDecimal.valueOf(800), true);
        // Desactivada (isActive=false) pero NO eliminada: tiene que subir igual, para que
        // cumpla la regla si el dueño la reactiva más adelante.
        Cancha canchaDesactivadaCero = canchaDePrueba(305L, BigDecimal.ZERO, false);
        List<Cancha> canchas = List.of(canchaCero, canchaDoscientos, canchaQuinientos, canchaOchocientos, canchaDesactivadaCero);
        when(canchaRepository.findByEstablecimientoIdIn(List.of(200L))).thenReturn(canchas);

        service.degradarPorVencimiento(11L);

        ArgumentCaptor<List<Cancha>> captor = ArgumentCaptor.forClass(List.class);
        verify(canchaRepository).saveAll(captor.capture());
        List<Long> idsAjustados = captor.getValue().stream().map(Cancha::getId).toList();
        assertEquals(List.of(301L, 302L, 305L), idsAjustados);

        assertEquals(BigDecimal.valueOf(500), canchaCero.getMontoSena());
        assertEquals(BigDecimal.valueOf(500), canchaDoscientos.getMontoSena());
        assertEquals(BigDecimal.valueOf(500), canchaDesactivadaCero.getMontoSena());
        assertEquals(BigDecimal.valueOf(500), canchaQuinientos.getMontoSena());
        assertEquals(BigDecimal.valueOf(800), canchaOchocientos.getMontoSena());
    }

    @Test
    @DisplayName("degradarPorVencimiento_CanchaEliminada_QuedaExcluidaPorElRepositorioYNoSeToca")
    void degradarPorVencimiento_CanchaEliminada_QuedaExcluidaPorElRepositorioYNoSeToca() {
        Usuario usuario = usuarioDePrueba(12L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(12L)).thenReturn(Optional.of(usuario));

        Establecimiento establecimiento = establecimientoDePrueba(210L, EstadoVerificacion.VERIFICADO);
        when(establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(12L)).thenReturn(List.of(establecimiento));

        // CanchaRepository.findByEstablecimientoIdIn ya filtra "deletedAt IS NULL" en su
        // @Query: una cancha eliminada directamente no aparece en este resultado, así que el
        // servicio nunca la recibe ni la toca.
        Cancha canchaEliminada = canchaDePrueba(311L, BigDecimal.ZERO, false);
        canchaEliminada.setDeletedAt(LocalDateTime.now());
        when(canchaRepository.findByEstablecimientoIdIn(List.of(210L))).thenReturn(List.of());

        service.degradarPorVencimiento(12L);

        verify(canchaRepository, never()).saveAll(anyList());
        assertEquals(BigDecimal.ZERO, canchaEliminada.getMontoSena());
    }

    @Test
    @DisplayName("degradarPorVencimiento_AjusteDeSenaDosVecesSeguidas_LaSegundaEsNoOp")
    void degradarPorVencimiento_AjusteDeSenaDosVecesSeguidas_LaSegundaEsNoOp() {
        Usuario usuario = usuarioDePrueba(13L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(13L)).thenReturn(Optional.of(usuario));

        Establecimiento establecimiento = establecimientoDePrueba(220L, EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(13L)).thenReturn(List.of(establecimiento));

        Cancha cancha = canchaDePrueba(321L, BigDecimal.ZERO, true);
        when(canchaRepository.findByEstablecimientoIdIn(List.of(220L))).thenReturn(List.of(cancha));

        service.degradarPorVencimiento(13L);
        service.degradarPorVencimiento(13L);

        verify(establecimientoRepository, times(1)).saveAll(anyList());
        verify(canchaRepository, times(1)).saveAll(anyList());
        assertTrue(establecimiento.getRequiereSena());
        assertEquals(BigDecimal.valueOf(500), cancha.getMontoSena());
    }

    @Test
    @DisplayName("degradarPorVencimiento_ConCanchasAjustadas_ElDetalleDeAuditoriaIncluyeElResumenDelAjuste")
    void degradarPorVencimiento_ConCanchasAjustadas_ElDetalleDeAuditoriaIncluyeElResumenDelAjuste() {
        Usuario usuario = usuarioDePrueba(14L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(14L)).thenReturn(Optional.of(usuario));

        Establecimiento establecimiento = establecimientoDePrueba(230L, EstadoVerificacion.PENDIENTE);
        when(establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(14L)).thenReturn(List.of(establecimiento));

        Cancha canchaCero = canchaDePrueba(331L, BigDecimal.ZERO, true);
        Cancha canchaOchocientos = canchaDePrueba(332L, BigDecimal.valueOf(800), true);
        when(canchaRepository.findByEstablecimientoIdIn(List.of(230L))).thenReturn(List.of(canchaCero, canchaOchocientos));

        service.degradarPorVencimiento(14L);

        ArgumentCaptor<AuditoriaDegradacionPlan> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaDegradacionPlan.class);
        verify(auditoriaDegradacionPlanRepository).save(auditoriaCaptor.capture());
        String detalle = auditoriaCaptor.getValue().getDetalle();
        assertTrue(detalle.contains("1 establecimiento"), detalle);
        assertTrue(detalle.contains("1 cancha"), detalle);
        assertTrue(detalle.contains("500"), detalle);
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

    private Establecimiento establecimientoDePrueba(Long id, EstadoVerificacion estadoVerificacion) {
        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo " + id)
                .direccion("Calle " + id)
                .latitud(0.0)
                .longitud(0.0)
                .requiereSena(false)
                .slug("complejo-" + id)
                .estadoVerificacion(estadoVerificacion)
                .build();
        establecimiento.setId(id);
        return establecimiento;
    }

    private Cancha canchaDePrueba(Long id, BigDecimal montoSena, boolean isActive) {
        Cancha cancha = Cancha.builder()
                .nombre("Cancha " + id)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(montoSena)
                .isActive(isActive)
                .build();
        cancha.setId(id);
        return cancha;
    }
}
