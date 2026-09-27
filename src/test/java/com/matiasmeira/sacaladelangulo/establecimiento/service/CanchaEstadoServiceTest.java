package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoCanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CambiarEstadoCanchaResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CanchaEstadoService es sólo el orquestador de PATCH /estado: arma la respuesta a partir de
 * lo que devuelven desactivarCancha/reactivarCancha, sin reimplementar ningún guard -- esos
 * viven en CanchaService (validarDesactivacion / validarConfiguracionDePool) y ya tienen su
 * propia cobertura en CanchaServiceDesactivacionReversibleTest / CanchaServiceReactivacionEstadoTest.
 * Este test sólo prueba el ruteo: activo=false -> desactivarCancha, activo=true -> reactivarCancha.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaEstadoService - orquestación de PATCH /estado")
class CanchaEstadoServiceTest {

    @Mock
    private CanchaService canchaService;

    @InjectMocks
    private CanchaEstadoService canchaEstadoService;

    @Test
    @DisplayName("cambiarEstado_ActivoFalse_DelegaEnDesactivarCancha")
    void cambiarEstado_ActivoFalse_DelegaEnDesactivarCancha() {
        Cancha canchaDesactivada = Cancha.builder().id(100L).isActive(false).build();
        when(canchaService.desactivarCancha(10L, 100L, "dueno@test.com")).thenReturn(canchaDesactivada);

        CambiarEstadoCanchaResponse response = canchaEstadoService.cambiarEstado(
                10L, 100L, new CambiarEstadoCanchaRequest(false), "dueno@test.com");

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.isActive()).isFalse();
        verify(canchaService, never()).reactivarCancha(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("cambiarEstado_ActivoTrue_DelegaEnReactivarCancha")
    void cambiarEstado_ActivoTrue_DelegaEnReactivarCancha() {
        Cancha canchaReactivada = Cancha.builder().id(100L).isActive(true).build();
        when(canchaService.reactivarCancha(10L, 100L, "dueno@test.com")).thenReturn(canchaReactivada);

        CambiarEstadoCanchaResponse response = canchaEstadoService.cambiarEstado(
                10L, 100L, new CambiarEstadoCanchaRequest(true), "dueno@test.com");

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.isActive()).isTrue();
        verify(canchaService, never()).desactivarCancha(anyLong(), anyLong(), any());
    }
}
