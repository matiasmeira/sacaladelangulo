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
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Mock
    private ReservaRepository reservaRepository;

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

        establecimiento = Establecimientos.establecimientoOperativo(b -> b
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true));

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

    private Cancha logicaInactiva(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .canchasFisicas(pool).canchasNecesarias(canchasNecesarias)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(false).build();
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

    /**
     * "Todas las canchas del establecimiento" que ve validarConfiguracionDePool: desde que
     * otrasLogicas es activas ∪ {inactivas con reserva futura vigente}, ya no alcanza con
     * mirar sólo activas (ver CanchaService.validarConfiguracionDePool).
     */
    private void mockCanchasActivasDelEstablecimiento(Cancha... canchas) {
        lenient().when(canchaRepository.findByEstablecimientoId(establecimiento.getId()))
                .thenReturn(List.of(canchas));
    }

    private CanchaRequest requestLogica(String nombre, List<Long> canchasFisicasIds, int cantidadCanchasNecesarias) {
        return new CanchaRequest(nombre, new HashSet<>(Set.of(Deporte.FUTBOL_5)), BigDecimal.valueOf(10000),
                BigDecimal.ZERO, null, null, true, null, canchasFisicasIds, cantidadCanchasNecesarias, null);
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

    /**
     * El escenario del diagnóstico: C9=[F1,F2,F3] está inactiva pero todavía tiene una
     * reserva futura vigente. Antes de este fix, el guard sólo miraba lógicas activas y
     * dejaba crear C7=[F1,F2] sin avisar nada, descubriendo el choque recién al reactivar
     * C9 (ver CanchaServiceReactivacionPoolGapDiagnosticoTest). Ahora se bloquea acá mismo,
     * en el momento en que se intenta crear la configuración inconsistente.
     */
    @Test
    @DisplayName("crearLogicaConPoolQueIntersectaParcialmente_OtraInactivaConReservaFutura_Rechaza")
    void crearLogicaConPoolQueIntersectaParcialmente_OtraInactivaConReservaFutura_Rechaza() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logicaInactiva(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        mockFisicasExistentes(f1, f2);
        mockCanchasActivasDelEstablecimiento(f1, f2, f3, c9);

        LocalDateTime ultimaReserva = LocalDateTime.now().plusDays(5).withHour(21).withMinute(0).withSecond(0).withNano(0);
        Reserva reservaFuturaC9 = Reserva.builder().id(900L).cancha(c9).estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(ultimaReserva.minusHours(1)).fechaHoraFin(ultimaReserva)
                .precioTotal(BigDecimal.TEN).senaPagada(BigDecimal.ZERO).build();
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of(reservaFuturaC9));

        assertThatThrownBy(() -> canchaService.crearCancha(
                establecimiento.getId(), requestLogica("Cancha de 7", List.of(1L, 2L), 2), dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 9")
                .hasMessageContaining(ultimaReserva.toLocalDate().toString());
    }

    /**
     * Misma configuración que el test anterior, pero C9 ya no tiene ninguna reserva futura:
     * la restricción caducó sola (ver el mensaje de error en el otro test, que avisa esto de
     * antemano) y crear C7 debe permitirse sin objeciones.
     */
    @Test
    @DisplayName("crearLogicaConPoolQueIntersectaParcialmente_OtraInactivaSinReservaFutura_Permite")
    void crearLogicaConPoolQueIntersectaParcialmente_OtraInactivaSinReservaFutura_Permite() {
        Cancha f1 = fisica(1), f2 = fisica(2), f3 = fisica(3);
        Cancha c9 = logicaInactiva(9, "Cancha de 9", Set.of(f1, f2, f3), 3);
        mockFisicasExistentes(f1, f2);
        mockCanchasActivasDelEstablecimiento(f1, f2, f3, c9);
        when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of());

        var response = canchaService.crearCancha(
                establecimiento.getId(), requestLogica("Cancha de 7", List.of(1L, 2L), 2), dueno.getEmail());

        assertThat(response.canchasFisicasIds()).containsExactlyInAnyOrder(1L, 2L);
    }
}
