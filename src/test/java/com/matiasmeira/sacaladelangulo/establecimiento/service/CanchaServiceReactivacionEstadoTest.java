package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CanchaService.reactivarCancha: contraparte de desactivarCancha para el camino nuevo de
 * PATCH /estado (ver CanchaEstadoService). Antes de este método la única forma de reactivar
 * era actualizarCancha con el CanchaRequest completo -- este método hace lo mismo, pero sin
 * exigir el resto de los campos de la cancha, y con el mismo guard de pool que ya corre ahí
 * (validarConfiguracionDePool), no el de desactivarDesactivacion (ver B19/estado-toggle en la
 * auditoría).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - reactivarCancha (PATCH /estado, activo=true)")
class CanchaServiceReactivacionEstadoTest {

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private CanchaService canchaService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(2L).email("dueno@test.com").rol(Role.OWNER).planSuscripcion(PlanSuscripcion.PREMIUM).build();
        establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(10L).nombre("Establecimiento Test").direccion("Calle Test 123")
                .latitud(-34.6037).longitud(-58.3816).dueno(dueno).requiereSena(true));
    }

    @Test
    @DisplayName("reactivarCancha_Exito_DejaLaCanchaActiva")
    void reactivarCancha_Exito_DejaLaCanchaActiva() {
        Cancha inactiva = Canchas.canchaDesactivada(establecimiento, b -> b.id(100L).nombre("Cancha A"));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(inactiva.getId())).thenReturn(Optional.of(inactiva));
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(inv -> inv.getArgument(0));

        Cancha resultado = assertDoesNotThrow(() ->
                canchaService.reactivarCancha(establecimiento.getId(), inactiva.getId(), dueno.getEmail()));

        assertThat(resultado.getIsActive()).isTrue();
        verify(canchaRepository).save(inactiva);
    }

    @Test
    @DisplayName("reactivarCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void reactivarCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        Cancha inactiva = Canchas.canchaDesactivada(establecimiento, b -> b.id(100L).nombre("Cancha A"));
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        assertThatThrownBy(() -> canchaService.reactivarCancha(establecimiento.getId(), inactiva.getId(), otroDueno.getEmail()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("reactivarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento")
    void reactivarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento() {
        Establecimiento otroEstablecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(99L).nombre("Otro").direccion("Otra calle").latitud(-1.0).longitud(-1.0)
                .dueno(dueno).requiereSena(true));
        Cancha inactivaDeOtroEstablecimiento = Canchas.canchaDesactivada(otroEstablecimiento, b -> b.id(200L).nombre("Cancha B"));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(inactivaDeOtroEstablecimiento.getId())).thenReturn(Optional.of(inactivaDeOtroEstablecimiento));

        assertThatThrownBy(() -> canchaService.reactivarCancha(establecimiento.getId(), inactivaDeOtroEstablecimiento.getId(), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("reactivarCancha_Fallo_CanchaNoEncontrada")
    void reactivarCancha_Fallo_CanchaNoEncontrada() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> canchaService.reactivarCancha(establecimiento.getId(), 999L, dueno.getEmail()))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /**
     * El agujero obvio de agregar "activo: true" a un endpoint de estado: si se buscara la
     * cancha con un findById a secas en vez de buscarCanchaNoEliminada, este PATCH podría
     * resucitar algo que CanchaEliminacionService ya dio de baja definitiva. buscarCanchaNoEliminada
     * excluye deletedAt != null y tira "no encontrada" -- mismo mensaje que un id inválido, sin
     * distinguirlo -- así que una cancha eliminada nunca vuelve a activarse por acá.
     */
    @Test
    @DisplayName("reactivarCancha_Fallo_CanchaEliminada_NoLaResucita")
    void reactivarCancha_Fallo_CanchaEliminada_NoLaResucita() {
        Cancha eliminada = Canchas.canchaEliminada(establecimiento, b -> b.id(300L).nombre("Cancha Eliminada"));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(300L)).thenReturn(Optional.of(eliminada));

        assertThatThrownBy(() -> canchaService.reactivarCancha(establecimiento.getId(), 300L, dueno.getEmail()))
                .isInstanceOf(EntityNotFoundException.class);
        verify(canchaRepository, never()).save(any());
    }

    /**
     * Mismo escenario que CanchaServiceReactivacionPoolGapDiagnosticoTest (dato ya inconsistente
     * en la base: C9=[F1,F2,F3] inactiva, C7=[F1,F2] activa parcialmente superpuesta), pero
     * probando el camino NUEVO (reactivarCancha) en vez de actualizarCancha con el PUT completo:
     * el guard de pool tiene que seguir bloqueando la reactivación sin importar por qué endpoint
     * se dispare.
     */
    @Test
    @DisplayName("reactivarCancha_Fallo_PoolSePisaParcialmenteConOtraLogicaActiva")
    void reactivarCancha_Fallo_PoolSePisaParcialmenteConOtraLogicaActiva() {
        Cancha f1 = fisica(1L, "F1");
        Cancha f2 = fisica(2L, "F2");
        Cancha f3 = fisica(3L, "F3");

        Cancha c9 = Cancha.builder().id(9L).nombre("Cancha de 9").establecimiento(establecimiento)
                .canchasFisicas(new HashSet<>(Set.of(f1, f2, f3))).canchasNecesarias(3)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(false).build();
        Cancha c7 = Cancha.builder().id(7L).nombre("Cancha de 7").establecimiento(establecimiento)
                .canchasFisicas(new HashSet<>(Set.of(f1, f2))).canchasNecesarias(2)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(c9.getId())).thenReturn(Optional.of(c9));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2, f3, c7));

        assertThatThrownBy(() -> canchaService.reactivarCancha(establecimiento.getId(), c9.getId(), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 7");
        verify(canchaRepository, never()).save(any());
    }

    /**
     * Contracara del anterior: si el grupo con el que se pisa está inactivo (no hay reserva
     * futura vigente que proteger), la reactivación no se bloquea -- mismo criterio que ya
     * corre para crear/actualizar.
     */
    @Test
    @DisplayName("reactivarCancha_Exito_CuandoElPoolQueSePisaEstaInactivoYSinReservasFuturas")
    void reactivarCancha_Exito_CuandoElPoolQueSePisaEstaInactivoYSinReservasFuturas() {
        Cancha f1 = fisica(1L, "F1");
        Cancha f2 = fisica(2L, "F2");

        Cancha c9 = Cancha.builder().id(9L).nombre("Cancha de 9").establecimiento(establecimiento)
                .canchasFisicas(new HashSet<>(Set.of(f1, f2))).canchasNecesarias(2)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(false).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(c9.getId())).thenReturn(Optional.of(c9));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2));
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(inv -> inv.getArgument(0));

        Cancha resultado = assertDoesNotThrow(() ->
                canchaService.reactivarCancha(establecimiento.getId(), c9.getId(), dueno.getEmail()));

        assertThat(resultado.getIsActive()).isTrue();
    }

    private Cancha fisica(long id, String nombre) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true)
                .canchasFisicas(new HashSet<>()).build();
    }
}
