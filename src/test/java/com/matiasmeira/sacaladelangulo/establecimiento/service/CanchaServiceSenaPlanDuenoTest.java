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
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La regla de seña de crearCancha/actualizarCancha depende del plan del DUEÑO del complejo,
 * no del plan del usuario autenticado: un ADMIN que edita lo de un dueño FREE no puede
 * saltearse la seña mínima, y uno FREE editando lo de un dueño PREMIUM no queda atado a ella.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - la regla de seña usa el plan del dueño del complejo")
class CanchaServiceSenaPlanDuenoTest {

    private static final String EMAIL = "quien-sea@test.com";

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

    private Usuario usuario(Long id, Role rol, PlanSuscripcion plan) {
        return Usuario.builder().id(id).email("u" + id + "@test.com").rol(rol).planSuscripcion(plan).build();
    }

    private Establecimiento complejoDe(Usuario dueno) {
        return Establecimientos.establecimientoOperativo(b -> b
                .id(10L).nombre("Complejo").direccion("Calle 1")
                .latitud(-34.6).longitud(-58.4).dueno(dueno).requiereSena(true));
    }

    private CanchaRequest request(BigDecimal sena) {
        return new CanchaRequest("Cancha A", Set.of(Deporte.FUTBOL_5), BigDecimal.valueOf(5000),
                sena, null, null, null, null, null, null, null);
    }

    private void prepararAutorizacion(Establecimiento complejo, Usuario autenticado) {
        when(establecimientoRepository.findById(complejo.getId())).thenReturn(Optional.of(complejo));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(complejo, EMAIL)).thenReturn(autenticado);
    }

    private Cancha canchaExistente(Establecimiento complejo) {
        return Cancha.builder().id(100L).nombre("Cancha A").establecimiento(complejo).isActive(true)
                .montoSena(BigDecimal.valueOf(800)).build();
    }

    private BigDecimal crearYCapturarSena(Establecimiento complejo, Usuario autenticado, BigDecimal sena) {
        prepararAutorizacion(complejo, autenticado);
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(i -> i.getArgument(0));
        canchaService.crearCancha(complejo.getId(), request(sena), EMAIL);
        ArgumentCaptor<Cancha> captor = ArgumentCaptor.forClass(Cancha.class);
        verify(canchaRepository).save(captor.capture());
        return captor.getValue().getMontoSena();
    }

    private BigDecimal editarYCapturarSena(Establecimiento complejo, Usuario autenticado, BigDecimal sena) {
        prepararAutorizacion(complejo, autenticado);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaExistente(complejo)));
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(i -> i.getArgument(0));
        canchaService.actualizarCancha(complejo.getId(), 100L, request(sena), EMAIL);
        ArgumentCaptor<Cancha> captor = ArgumentCaptor.forClass(Cancha.class);
        verify(canchaRepository).save(captor.capture());
        return captor.getValue().getMontoSena();
    }

    private void crearEsperandoRechazo(Establecimiento complejo, Usuario autenticado, BigDecimal sena) {
        prepararAutorizacion(complejo, autenticado);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> canchaService.crearCancha(complejo.getId(), request(sena), EMAIL));
        assertEquals("El plan del complejo requiere configurar una seña obligatoria de mínimo $500", ex.getMessage());
        verify(canchaRepository, never()).save(any());
    }

    private void editarEsperandoRechazo(Establecimiento complejo, Usuario autenticado, BigDecimal sena) {
        prepararAutorizacion(complejo, autenticado);
        when(canchaRepository.findById(100L)).thenReturn(Optional.of(canchaExistente(complejo)));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> canchaService.actualizarCancha(complejo.getId(), 100L, request(sena), EMAIL));
        assertEquals("El plan del complejo requiere configurar una seña obligatoria de mínimo $500", ex.getMessage());
        verify(canchaRepository, never()).save(any());
    }

    // ---- crear ----

    @Test
    @DisplayName("crearCancha_AdminPremiumEnComplejoDeDuenoFree_SenaNull_Falla")
    void crearCancha_AdminPremiumEnComplejoDeDuenoFree_SenaNull_Falla() {
        crearEsperandoRechazo(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), null);
    }

    @Test
    @DisplayName("crearCancha_AdminPremiumEnComplejoDeDuenoFree_Sena499_Falla")
    void crearCancha_AdminPremiumEnComplejoDeDuenoFree_Sena499_Falla() {
        crearEsperandoRechazo(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), BigDecimal.valueOf(499));
    }

    @Test
    @DisplayName("crearCancha_AdminPremiumEnComplejoDeDuenoFree_Sena500_Persiste500")
    void crearCancha_AdminPremiumEnComplejoDeDuenoFree_Sena500_Persiste500() {
        BigDecimal guardada = crearYCapturarSena(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), BigDecimal.valueOf(500));
        assertEquals(0, BigDecimal.valueOf(500).compareTo(guardada));
    }

    @Test
    @DisplayName("crearCancha_AdminFreeEnComplejoDeDuenoPremium_Sena0_Persiste0")
    void crearCancha_AdminFreeEnComplejoDeDuenoPremium_Sena0_Persiste0() {
        BigDecimal guardada = crearYCapturarSena(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.PREMIUM)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.FREE), BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(guardada));
    }

    @Test
    @DisplayName("crearCancha_DuenoFreeEnLoSuyo_SenaMenorAlMinimo_Falla")
    void crearCancha_DuenoFreeEnLoSuyo_SenaMenorAlMinimo_Falla() {
        Usuario dueno = usuario(2L, Role.OWNER, PlanSuscripcion.FREE);
        crearEsperandoRechazo(complejoDe(dueno), dueno, BigDecimal.valueOf(100));
    }

    // ---- editar ----

    @Test
    @DisplayName("actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_SenaNull_Falla")
    void actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_SenaNull_Falla() {
        editarEsperandoRechazo(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), null);
    }

    @Test
    @DisplayName("actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_Sena499_Falla")
    void actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_Sena499_Falla() {
        editarEsperandoRechazo(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), BigDecimal.valueOf(499));
    }

    @Test
    @DisplayName("actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_Sena500_Persiste500")
    void actualizarCancha_AdminPremiumEnComplejoDeDuenoFree_Sena500_Persiste500() {
        BigDecimal guardada = editarYCapturarSena(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.FREE)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.PREMIUM), BigDecimal.valueOf(500));
        assertEquals(0, BigDecimal.valueOf(500).compareTo(guardada));
    }

    @Test
    @DisplayName("actualizarCancha_AdminFreeEnComplejoDeDuenoPremium_Sena0_Persiste0")
    void actualizarCancha_AdminFreeEnComplejoDeDuenoPremium_Sena0_Persiste0() {
        BigDecimal guardada = editarYCapturarSena(complejoDe(usuario(2L, Role.OWNER, PlanSuscripcion.PREMIUM)),
                usuario(1L, Role.ADMIN, PlanSuscripcion.FREE), BigDecimal.ZERO);
        assertEquals(0, BigDecimal.ZERO.compareTo(guardada));
    }

    @Test
    @DisplayName("actualizarCancha_DuenoFreeEnLoSuyo_SenaMenorAlMinimo_Falla")
    void actualizarCancha_DuenoFreeEnLoSuyo_SenaMenorAlMinimo_Falla() {
        Usuario dueno = usuario(2L, Role.OWNER, PlanSuscripcion.FREE);
        editarEsperandoRechazo(complejoDe(dueno), dueno, BigDecimal.valueOf(100));
    }
}
