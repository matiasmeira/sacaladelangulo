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
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regresión, no diagnóstico: este escenario alguna vez documentó un gap
 * (validarConfiguracionDePool sólo miraba lógicas ACTIVAS, así que C7=[F1,F2] podía crearse
 * sin objeciones mientras C9=[F1,F2,F3] estaba inactiva, y el choque parcial recién se
 * descubría al reactivar C9). Ese gap ya está cerrado en el punto de creación (ver
 * CanchaServiceValidacionPoolTest.crearLogicaConPoolQueIntersectaParcialmente_OtraInactivaConReservaFutura_Rechaza):
 * hoy, crear C7 en esas condiciones se bloquea ahí mismo.
 *
 * Este test cubre lo que queda: si la base YA tiene ese par inconsistente (dato legacy, de
 * antes del fix, o insertado por otra vía) — ver la consulta SQL para detectar esos pares
 * en producción —, reactivar la lógica inactiva se sigue bloqueando. Es la protección de
 * última línea: validarConfiguracionDePool nunca corrige retroactivamente una config ya
 * persistida, sólo impide sumar una nueva sobre una base inconsistente.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - reactivación sobre un par de pools ya inconsistente (dato legacy)")
class CanchaServiceReactivacionPoolGapDiagnosticoTest {

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
        dueno = Usuario.builder().id(2L).email("dueno@test.com").rol(Role.OWNER).planSuscripcion(PlanSuscripcion.PREMIUM).build();
        establecimiento = Establecimiento.builder().id(10L).nombre("Complejo Test").direccion("Calle 123")
                .latitud(-34.6).longitud(-58.4).dueno(dueno).requiereSena(true).isActive(true).build();

        lenient().when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        lenient().when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
    }

    private Cancha fisica(long id, String nombre) {
        return Cancha.builder().id(id).nombre(nombre).establecimiento(establecimiento)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true).build();
    }

    @Test
    @DisplayName("reactivarLogicaSobrePoolYaInconsistenteEnLaBase_SigueBloqueada")
    void reactivarLogicaSobrePoolYaInconsistenteEnLaBase_SigueBloqueada() {
        Cancha f1 = fisica(1L, "F1");
        Cancha f2 = fisica(2L, "F2");
        Cancha f3 = fisica(3L, "F3");

        // Estado legacy ya en la base: C9=[F1,F2,F3] inactiva, C7=[F1,F2] activa y parcialmente
        // superpuesta -- exactamente el par que detecta la consulta SQL del punto 1.
        Cancha c9 = Cancha.builder().id(9L).nombre("Cancha de 9").establecimiento(establecimiento)
                .canchasFisicas(new HashSet<>(Set.of(f1, f2, f3))).canchasNecesarias(3)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(false).build();
        Cancha c7 = Cancha.builder().id(7L).nombre("Cancha de 7").establecimiento(establecimiento)
                .canchasFisicas(new HashSet<>(Set.of(f1, f2))).canchasNecesarias(2)
                .precioBase(BigDecimal.TEN).montoSena(BigDecimal.ZERO).isActive(true).build();

        when(canchaRepository.findById(c9.getId())).thenReturn(Optional.of(c9));
        when(canchaRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(f1, f2, f3));
        when(canchaRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(f1, f2, f3, c7));
        lenient().when(reservaRepository.findFuturasPorCanchaIds(any(), any())).thenReturn(List.of());

        CanchaRequest reactivar = new CanchaRequest("Cancha de 9", new HashSet<>(Set.of(Deporte.FUTBOL_5)),
                BigDecimal.valueOf(10000), BigDecimal.ZERO, null, null, true, null,
                List.of(1L, 2L, 3L), 3, true);

        // C7 está ACTIVA, así que bloquea por la rama de siempre (choque entre dos lógicas
        // activas), sin necesidad de que C9 tenga reservas futuras: el guard no retrocede
        // sobre una inconsistencia que ya existe en la base.
        assertThatThrownBy(() -> canchaService.actualizarCancha(establecimiento.getId(), c9.getId(), reactivar, dueno.getEmail()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cancha de 7");
    }
}
