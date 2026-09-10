package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.CanchaRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoDetalleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Guard de configuración: la suma-por-grupo de PoolCanchaCalculator (ver
 * PoolCanchaCalculatorGrupoFisicasTest) solo es exacta si los pools de un mismo grupo son
 * homogéneos (idénticos). Sin este guard, dar de alta una lógica cuyo pool se pisa
 * PARCIALMENTE con el de otra lógica activa sobrevende en silencio. Se valida solo en
 * altas/ediciones (crearCancha/actualizarCancha), nunca retroactivamente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - Guard de configuración de pools")
class CanchaServiceValidacionPoolTest {

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private ComplejoDetalleCache complejoDetalleCache;

    @InjectMocks
    private CanchaService canchaService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        lenient().when(canchaRepository.save(any(Cancha.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Cancha fisica(long id) {
        return Cancha.builder().id(id).nombre("F" + id).establecimiento(establecimiento)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true).build();
    }

    private Cancha logicaActiva(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .canchasFisicas(pool).canchasNecesarias(canchasNecesarias)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true).build();
    }

    private void mockFisicasExistentes(Cancha... fisicas) {
        Map<Long, Cancha> porId = new HashMap<>();
        for (Cancha f : fisicas) {
            porId.put(f.getId(), f);
        }
        when(canchaRepository.findAllById(any())).thenAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<Cancha> resultado = new ArrayList<>();
            for (Long id : ids) {
                resultado.add(porId.get(id));
            }
            return resultado;
        });
    }

    private void mockCanchasActivasDelEstablecimiento(Cancha... canchas) {
        lenient().when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(canchas));
    }

    private CanchaRequest requestLogica(String nombre, List<Long> canchasFisicasIds, int cantidadCanchasNecesarias) {
        return new CanchaRequest(nombre, new HashSet<>(Set.of(Deporte.FUTBOL_5)), BigDecimal.valueOf(10000),
                BigDecimal.ZERO, null, null, true, null, canchasFisicasIds, cantidadCanchasNecesarias);
    }

    @Test
    @DisplayName("crearLogicaConPoolQueIntersectaParcialmenteOtroPoolActivo_Rechaza")
    void crearLogicaConPoolQueIntersectaParcialmenteOtroPoolActivo_Rechaza() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha existente = logicaActiva(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        mockFisicasExistentes(f1, f2);
        mockCanchasActivasDelEstablecimiento(f1, f2, f3, existente);

        assertThatThrownBy(() -> canchaService.crearCancha(
                establecimiento.getId(), requestLogica("Cancha de 7", List.of(1L, 2L), 2), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 9");
    }

    @Test
    @DisplayName("crearSegundaLogicaConPoolIdenticoAUnaActiva_Permite")
    void crearSegundaLogicaConPoolIdenticoAUnaActiva_Permite() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha existente = logicaActiva(9, "Cancha de 9 (A)", Set.of(f1, f2, f3), 3);
        mockFisicasExistentes(f1, f2, f3);
        mockCanchasActivasDelEstablecimiento(f1, f2, f3, existente);

        var response = canchaService.crearCancha(
                establecimiento.getId(), requestLogica("Cancha de 9 (B)", List.of(1L, 2L, 3L), 3), dueno.getEmail());

        assertThat(response.canchasFisicasIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    @DisplayName("crearLogicaCuyoPoolIncluyeOtraLogica_Rechaza")
    void crearLogicaCuyoPoolIncluyeOtraLogica_Rechaza() {
        Cancha f1 = fisica(1), f2 = fisica(2);
        Cancha otraLogica = logicaActiva(5, "Combo chico", Set.of(f1, f2), 2);
        mockFisicasExistentes(f1, otraLogica);
        mockCanchasActivasDelEstablecimiento(f1, f2, otraLogica);

        assertThatThrownBy(() -> canchaService.crearCancha(
                establecimiento.getId(), requestLogica("Combo grande", List.of(1L, 5L), 2), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
