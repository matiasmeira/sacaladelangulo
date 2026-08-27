package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobertura acotada a desactivarCancha (ver B19 en la auditoría): antes no existía
 * ningún test de CanchaService, y agregar cobertura del resto de sus métodos
 * (crearCancha/actualizarCancha) queda fuera del alcance de este hallazgo puntual.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CanchaService - Tests de desactivación de cancha")
class CanchaServiceTest {

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
    private Cancha cancha;

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

        cancha = Cancha.builder()
                .id(100L)
                .nombre("Cancha A")
                .establecimiento(establecimiento)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("desactivarCancha_Exito_DejaLaCanchaInactiva")
    void desactivarCancha_Exito_DejaLaCanchaInactiva() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> canchaService.desactivarCancha(establecimiento.getId(), cancha.getId(), dueno.getEmail()));

        assertFalse(cancha.getIsActive());
        verify(canchaRepository).save(cancha);
    }

    @Test
    @DisplayName("desactivarCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void desactivarCancha_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> canchaService.desactivarCancha(establecimiento.getId(), cancha.getId(), otroDueno.getEmail())
        );
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("desactivarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento")
    void desactivarCancha_Fallo_CanchaNoPerteneceAlEstablecimiento() {
        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(99L)
                .nombre("Otro")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        Cancha canchaDeOtroEstablecimiento = Cancha.builder()
                .id(200L)
                .nombre("Cancha B")
                .establecimiento(otroEstablecimiento)
                .isActive(true)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(canchaDeOtroEstablecimiento.getId())).thenReturn(Optional.of(canchaDeOtroEstablecimiento));

        assertThrows(
                IllegalArgumentException.class,
                () -> canchaService.desactivarCancha(establecimiento.getId(), canchaDeOtroEstablecimiento.getId(), dueno.getEmail())
        );
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("desactivarCancha_Fallo_CanchaNoEncontrada")
    void desactivarCancha_Fallo_CanchaNoEncontrada() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> canchaService.desactivarCancha(establecimiento.getId(), 999L, dueno.getEmail())
        );
    }

    private CanchaRequest requestConPreciosPorDuracion(Map<Integer, BigDecimal> preciosPorDuracion) {
        return new CanchaRequest(
                "Cancha A",
                Set.of(Deporte.FUTBOL_5),
                BigDecimal.valueOf(10000),
                BigDecimal.ZERO,
                java.util.List.of(60, 90, 120),
                preciosPorDuracion,
                true,
                java.util.List.of(),
                null,
                null
        );
    }

    private CanchaRequest requestConCanchasFisicasIds(java.util.List<Long> canchasFisicasIds) {
        return new CanchaRequest(
                "Cancha Pool",
                Set.of(Deporte.FUTBOL_5),
                BigDecimal.valueOf(10000),
                BigDecimal.ZERO,
                java.util.List.of(60, 90, 120),
                null,
                true,
                java.util.List.of(),
                canchasFisicasIds,
                canchasFisicasIds.size()
        );
    }

    @Test
    @DisplayName("crearCancha_Exito_ConPreciosPorDuracionValidos")
    void crearCancha_Exito_ConPreciosPorDuracionValidos() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CanchaRequest request = requestConPreciosPorDuracion(Map.of(120, BigDecimal.valueOf(18000)));

        var response = canchaService.crearCancha(establecimiento.getId(), request, dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(18000).compareTo(response.preciosPorDuracion().get(120)));
        verify(canchaRepository).save(any(Cancha.class));
        // Ver §3 "Consistencia entre features" en la auditoría: alta de cancha (con su
        // precio) por el dueño ahora deja rastro en RegistroAuditoria.
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.CREAR_CANCHA), any(), any());
    }

    @Test
    @DisplayName("crearCancha_Fallo_PrecioPorDuracionNoPermitida")
    void crearCancha_Fallo_PrecioPorDuracionNoPermitida() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        CanchaRequest request = requestConPreciosPorDuracion(Map.of(45, BigDecimal.valueOf(5000)));

        assertThrows(IllegalArgumentException.class,
                () -> canchaService.crearCancha(establecimiento.getId(), request, dueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearCancha_Fallo_PrecioPorDuracionNoPositivo")
    void crearCancha_Fallo_PrecioPorDuracionNoPositivo() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        CanchaRequest request = requestConPreciosPorDuracion(Map.of(120, BigDecimal.ZERO));

        assertThrows(IllegalArgumentException.class,
                () -> canchaService.crearCancha(establecimiento.getId(), request, dueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }

    @Test
    @DisplayName("crearCancha_Exito_ConCanchasFisicasDelMismoEstablecimiento")
    void crearCancha_Exito_ConCanchasFisicasDelMismoEstablecimiento() {
        Cancha fisicaA = Cancha.builder().id(101L).nombre("Fisica A").establecimiento(establecimiento).isActive(true).build();
        Cancha fisicaB = Cancha.builder().id(102L).nombre("Fisica B").establecimiento(establecimiento).isActive(true).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findAllById(java.util.List.of(101L, 102L))).thenReturn(java.util.List.of(fisicaA, fisicaB));
        when(canchaRepository.save(any(Cancha.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CanchaRequest request = requestConCanchasFisicasIds(java.util.List.of(101L, 102L));

        assertDoesNotThrow(() -> canchaService.crearCancha(establecimiento.getId(), request, dueno.getEmail()));
        verify(canchaRepository).save(any(Cancha.class));
        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.CREAR_CANCHA), any(), any());
    }

    @Test
    @DisplayName("crearCancha_Fallo_CanchaFisicaPerteneceAOtroEstablecimiento")
    void crearCancha_Fallo_CanchaFisicaPerteneceAOtroEstablecimiento() {
        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(99L)
                .nombre("Otro")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();
        Cancha fisicaDeOtroEstablecimiento = Cancha.builder()
                .id(201L).nombre("Ajena").establecimiento(otroEstablecimiento).isActive(true).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(canchaRepository.findAllById(java.util.List.of(201L))).thenReturn(java.util.List.of(fisicaDeOtroEstablecimiento));

        CanchaRequest request = requestConCanchasFisicasIds(java.util.List.of(201L));

        assertThrows(IllegalArgumentException.class,
                () -> canchaService.crearCancha(establecimiento.getId(), request, dueno.getEmail()));
        verify(canchaRepository, never()).save(any());
    }
}
