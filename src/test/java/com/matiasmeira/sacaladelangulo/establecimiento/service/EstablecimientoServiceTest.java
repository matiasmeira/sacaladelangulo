package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EstablecimientoServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @InjectMocks
    private EstablecimientoService establecimientoService;

    @Test
    @DisplayName("buscarEstablecimientos sin fecha y hora devuelve resultados cercanos")
    void buscarEstablecimientosSinFechaYHoraDevuelveResultadosCercanos() {
        Usuario dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .build();

        Establecimiento establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Cancha Premium")
                .direccion("Av. Siempre Viva 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build();

        when(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null))
                .thenReturn(List.of(establecimiento));

        List<EstablecimientoResponse> resultados = establecimientoService.buscarEstablecimientos(
                -34.6037,
                -58.3816,
                10.0,
                null,
                null,
                null
        );

        assertEquals(1, resultados.size());
        assertEquals(10L, resultados.get(0).id());
        assertEquals("Cancha Premium", resultados.get(0).nombre());
    }
}
