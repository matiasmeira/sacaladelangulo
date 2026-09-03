package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiracionPruebaService - Recorrido paginado de degradación")
class ExpiracionPruebaServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private DegradacionPlanService degradacionPlanService;

    @InjectMocks
    private ExpiracionPruebaService service;

    @Test
    @DisplayName("degradarPruebasVencidas_SinUsuariosVencidos_NoLlamaAlDegradador")
    void degradarPruebasVencidas_SinUsuariosVencidos_NoLlamaAlDegradador() {
        ReflectionTestUtils.setField(service, "tamanioLote", 100);
        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.degradarPruebasVencidas();

        verify(degradacionPlanService, never()).degradarPorVencimiento(anyLong());
    }

    @Test
    @DisplayName("degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_ProcesaTodosEnVariasPaginas")
    void degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_ProcesaTodosEnVariasPaginas() {
        ReflectionTestUtils.setField(service, "tamanioLote", 2);

        Usuario u1 = usuarioDePrueba(1L);
        Usuario u2 = usuarioDePrueba(2L);
        Usuario u3 = usuarioDePrueba(3L);

        Page<Usuario> primeraPagina = new PageImpl<>(List.of(u1, u2), Pageable.ofSize(2), 3);
        Page<Usuario> segundaPagina = new PageImpl<>(List.of(u3), Pageable.ofSize(2), 3);

        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(primeraPagina)
                .thenReturn(segundaPagina)
                .thenReturn(Page.empty());

        service.degradarPruebasVencidas();

        verify(degradacionPlanService).degradarPorVencimiento(1L);
        verify(degradacionPlanService).degradarPorVencimiento(2L);
        verify(degradacionPlanService).degradarPorVencimiento(3L);
    }

    @Test
    @DisplayName("degradarPruebasVencidas_UnUsuarioFalla_SigueProcesandoAlRestoDelLote")
    void degradarPruebasVencidas_UnUsuarioFalla_SigueProcesandoAlRestoDelLote() {
        ReflectionTestUtils.setField(service, "tamanioLote", 100);

        Usuario u1 = usuarioDePrueba(1L);
        Usuario u2 = usuarioDePrueba(2L);
        Usuario u3 = usuarioDePrueba(3L);

        Page<Usuario> pagina = new PageImpl<>(List.of(u1, u2, u3), Pageable.ofSize(100), 3);

        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(pagina)
                .thenReturn(Page.empty());

        // lenient(): con strict stubbing (default de MockitoExtension), stubear
        // degradarPorVencimiento solo para el arg 2L hace que Mockito trate las
        // invocaciones con 1L/3L (que no matchean ningún stub) como un posible error
        // del test y lance PotentialStubbingProblem en vez de no-opear. Como esa
        // excepción también es una RuntimeException, el catch de
        // ExpiracionPruebaService la absorbe igual que la intencional, así que el test
        // "pasaba" sin verificar realmente el escenario (solo usuario 2 falla, el
        // resto tiene éxito). lenient() saca este stub del chequeo estricto de
        // argumentos para que 1L y 3L caigan en el no-op por defecto de un método void.
        lenient().doThrow(new RuntimeException("fallo simulado"))
                .when(degradacionPlanService).degradarPorVencimiento(2L);

        service.degradarPruebasVencidas();

        verify(degradacionPlanService, times(1)).degradarPorVencimiento(1L);
        verify(degradacionPlanService, times(1)).degradarPorVencimiento(2L);
        verify(degradacionPlanService, times(1)).degradarPorVencimiento(3L);
    }

    @Test
    @DisplayName("degradarPruebasVencidas_UsuarioFallaPersistentemente_NoLoopeaInfinitamente")
    void degradarPruebasVencidas_UsuarioFallaPersistentemente_NoLoopeaInfinitamente() {
        ReflectionTestUtils.setField(service, "tamanioLote", 100);

        Usuario u1 = usuarioDePrueba(1L);
        Page<Usuario> pagina = new PageImpl<>(List.of(u1), Pageable.ofSize(100), 1);

        // El mismo usuario nunca sale de TRIAL porque su degradación siempre falla, así
        // que sin la protección de idsIntentados el repository volvería a traerlo en
        // cada vuelta del while: un solo stub (sin encadenar Page.empty()) simula esa
        // página que nunca se achica.
        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(pagina);
        doThrow(new RuntimeException("fallo simulado persistente"))
                .when(degradacionPlanService).degradarPorVencimiento(1L);

        service.degradarPruebasVencidas();

        verify(degradacionPlanService, times(1)).degradarPorVencimiento(1L);
    }

    private Usuario usuarioDePrueba(Long id) {
        Usuario usuario = Usuario.builder()
                .email("usuario" + id + "@test.com")
                .password("hash")
                .nombre("Usuario " + id)
                .build();
        usuario.setId(id);
        return usuario;
    }
}
