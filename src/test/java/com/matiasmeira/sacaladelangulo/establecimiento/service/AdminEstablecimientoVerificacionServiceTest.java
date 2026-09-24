package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.AdminEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests de la resolución de verificación manual. La invalidación de caché y el envío de
 * emails AFTER_COMMIT se delegan a colaboradores (ComplejoDetalleCache, el
 * ApplicationEventPublisher) que ya tienen su propia cobertura de esa mecánica -- acá solo se
 * verifica que este service los invoque correctamente, mismo criterio que
 * FotoEstablecimientoServiceTest usa para ComplejoDetalleCache.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminEstablecimientoVerificacionService")
class AdminEstablecimientoVerificacionServiceTest {

    private static final String EMAIL_ADMIN = "admin@test.com";

    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private UsuarioRepository usuarioRepository;
    @Mock
    private RegistroAuditoriaService registroAuditoriaService;
    @Mock
    private ComplejoDetalleCache complejoDetalleCache;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminEstablecimientoVerificacionService service;

    private Usuario admin;

    @BeforeEach
    void setUp() {
        admin = Usuario.builder().id(99L).email(EMAIL_ADMIN).rol(Role.ADMIN).build();
        when(usuarioRepository.findByEmail(EMAIL_ADMIN)).thenReturn(Optional.of(admin));
    }

    @Test
    @DisplayName("verificar_EstablecimientoEnRevisionYDuenoSinTrialIniciado_LoVerificaYArrancaElTrial")
    void verificar_EstablecimientoEnRevisionYDuenoSinTrialIniciado_LoVerificaYArrancaElTrial() {
        Usuario dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL).fechaFinPrueba(null).build();
        Establecimiento establecimiento = establecimientoEnRevision(10L, dueno);
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));

        service.verificar(10L, EMAIL_ADMIN);

        assertThat(establecimiento.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.VERIFICADO);
        assertThat(establecimiento.getFechaVerificacion()).isNotNull();
        assertThat(establecimiento.getVerificadoPor()).isEqualTo(admin);
        assertThat(establecimiento.getMotivoRechazo()).isNull();
        verify(establecimientoRepository).save(establecimiento);

        assertThat(dueno.getFechaFinPrueba()).isNotNull();
        assertThat(dueno.getFechaFinPrueba()).isAfter(LocalDateTime.now().plusDays(29));
        verify(usuarioRepository).save(dueno);

        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(admin), eq(establecimiento), eq(AccionAuditoria.VERIFICAR_ESTABLECIMIENTO), eq(10L), any());
        verify(complejoDetalleCache).invalidarPorEstablecimientoId(10L);
        verify(eventPublisher).publishEvent(new EstablecimientoVerificadoEvent(1L, establecimiento.getNombre()));
    }

    @Test
    @DisplayName("verificar_DuenoYaTieneFechaFinPrueba_NoLaToca")
    void verificar_DuenoYaTieneFechaFinPrueba_NoLaToca() {
        LocalDateTime fechaExistente = LocalDateTime.now().plusDays(10);
        Usuario dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL).fechaFinPrueba(fechaExistente).build();
        Establecimiento establecimiento = establecimientoEnRevision(11L, dueno);
        when(establecimientoRepository.findById(11L)).thenReturn(Optional.of(establecimiento));

        service.verificar(11L, EMAIL_ADMIN);

        assertThat(dueno.getFechaFinPrueba()).isEqualTo(fechaExistente);
        verify(usuarioRepository, never()).save(dueno);
    }

    @Test
    @DisplayName("verificar_DuenoYaNoEstaEnTrial_NoLeSeteaFechaFinPrueba")
    void verificar_DuenoYaNoEstaEnTrial_NoLeSeteaFechaFinPrueba() {
        Usuario dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.FREE).fechaFinPrueba(null).build();
        Establecimiento establecimiento = establecimientoEnRevision(12L, dueno);
        when(establecimientoRepository.findById(12L)).thenReturn(Optional.of(establecimiento));

        service.verificar(12L, EMAIL_ADMIN);

        assertThat(dueno.getFechaFinPrueba()).isNull();
        assertThat(dueno.getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
        verify(usuarioRepository, never()).save(dueno);
    }

    @Test
    @DisplayName("verificar_EstablecimientoYaVerificado_RechazaLaTransicionYNoTocaNada")
    void verificar_EstablecimientoYaVerificado_RechazaLaTransicionYNoTocaNada() {
        Usuario dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL).build();
        Establecimiento establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(13L).nombre("Complejo").dueno(dueno));
        when(establecimientoRepository.findById(13L)).thenReturn(Optional.of(establecimiento));

        assertThatThrownBy(() -> service.verificar(13L, EMAIL_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);

        verify(establecimientoRepository, never()).save(any());
        verify(usuarioRepository, never()).save(any());
        verify(registroAuditoriaService, never()).registrarSobreEstablecimiento(any(), any(), any(), any(), any());
        verify(complejoDetalleCache, never()).invalidarPorEstablecimientoId(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("verificar_EstablecimientoPendiente_RechazaLaTransicion")
    void verificar_EstablecimientoPendiente_RechazaLaTransicion() {
        Establecimiento establecimiento = Establecimientos.establecimientoPendiente(b -> b
                .id(14L).nombre("Complejo")
                .dueno(Usuario.builder().id(1L).build()));
        when(establecimientoRepository.findById(14L)).thenReturn(Optional.of(establecimiento));

        assertThatThrownBy(() -> service.verificar(14L, EMAIL_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("verificar_EstablecimientoInexistente_LanzaEntityNotFoundException")
    void verificar_EstablecimientoInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verificar(999L, EMAIL_ADMIN))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("rechazar_EstablecimientoEnRevision_GuardaElMotivoYNoTocaElTrialNiLaCache")
    void rechazar_EstablecimientoEnRevision_GuardaElMotivoYNoTocaElTrialNiLaCache() {
        Usuario dueno = Usuario.builder().id(1L).email("dueno@test.com").rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL).fechaFinPrueba(null).build();
        Establecimiento establecimiento = establecimientoEnRevision(15L, dueno);
        when(establecimientoRepository.findById(15L)).thenReturn(Optional.of(establecimiento));

        service.rechazar(15L, "Faltan fotos del frente del local", EMAIL_ADMIN);

        assertThat(establecimiento.getEstadoVerificacion()).isEqualTo(EstadoVerificacion.RECHAZADO);
        assertThat(establecimiento.getMotivoRechazo()).isEqualTo("Faltan fotos del frente del local");
        assertThat(establecimiento.getFechaVerificacion()).isNull();
        assertThat(dueno.getFechaFinPrueba()).isNull();

        verify(usuarioRepository, never()).save(any());
        verify(complejoDetalleCache, never()).invalidarPorEstablecimientoId(any());
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(admin), eq(establecimiento), eq(AccionAuditoria.RECHAZAR_ESTABLECIMIENTO), eq(15L), any());
        verify(eventPublisher).publishEvent(
                new EstablecimientoRechazadoEvent(1L, establecimiento.getNombre(), "Faltan fotos del frente del local"));
    }

    @Test
    @DisplayName("rechazar_EstablecimientoPendiente_RechazaLaTransicion")
    void rechazar_EstablecimientoPendiente_RechazaLaTransicion() {
        Establecimiento establecimiento = Establecimientos.establecimientoPendiente(b -> b
                .id(16L).nombre("Complejo")
                .dueno(Usuario.builder().id(1L).build()));
        when(establecimientoRepository.findById(16L)).thenReturn(Optional.of(establecimiento));

        assertThatThrownBy(() -> service.rechazar(16L, "motivo", EMAIL_ADMIN))
                .isInstanceOf(IllegalArgumentException.class);

        verify(establecimientoRepository, never()).save(any());
    }

    @Test
    @DisplayName("listar_ConFiltro_UsaFindByEstadoVerificacion")
    void listar_ConFiltro_UsaFindByEstadoVerificacion() {
        Pageable pageable = PageRequest.of(0, 20);
        when(establecimientoRepository.findByEstadoVerificacion(EstadoVerificacion.EN_REVISION, pageable))
                .thenReturn(new PageImpl<>(List.of()));

        service.listar(EstadoVerificacion.EN_REVISION, pageable);

        verify(establecimientoRepository).findByEstadoVerificacion(EstadoVerificacion.EN_REVISION, pageable);
        verify(establecimientoRepository, never()).findAllBy(any());
    }

    @Test
    @DisplayName("listar_SinFiltro_UsaFindAllByYMapeaAlDueno")
    void listar_SinFiltro_UsaFindAllByYMapeaAlDueno() {
        Pageable pageable = PageRequest.of(0, 20);
        Usuario dueno = Usuario.builder().id(1L).nombre("Carlos Dueño").email("dueno@test.com").build();
        Establecimiento establecimiento = establecimientoEnRevision(1L, dueno);
        when(establecimientoRepository.findAllBy(pageable)).thenReturn(new PageImpl<>(List.of(establecimiento)));

        Page<AdminEstablecimientoResponse> resultado = service.listar(null, pageable);

        assertThat(resultado.getContent()).hasSize(1);
        AdminEstablecimientoResponse fila = resultado.getContent().get(0);
        assertThat(fila.duenoId()).isEqualTo(1L);
        assertThat(fila.duenoNombre()).isEqualTo("Carlos Dueño");
        assertThat(fila.duenoEmail()).isEqualTo("dueno@test.com");
        assertThat(fila.estadoVerificacion()).isEqualTo(EstadoVerificacion.EN_REVISION);
        verify(establecimientoRepository, never()).findByEstadoVerificacion(any(), any());
    }

    private Establecimiento establecimientoEnRevision(Long id, Usuario dueno) {
        return Establecimientos.establecimientoEnRevision(b -> b
                .id(id)
                .nombre("Complejo " + id)
                .dueno(dueno));
    }
}
