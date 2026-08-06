package com.matiasmeira.sacaladelangulo.gastos.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoMapper;
import com.matiasmeira.sacaladelangulo.gastos.dto.GastoRequest;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Casos adversariales de Gasto no cubiertos por GastoServiceTest: montos extremos, campos
 * "obligatorios" (descripción, método de pago) que en realidad solo se validan en el DTO
 * (@Valid del controller) y NO se re-chequean en el service — a diferencia del monto, que sí
 * tiene un chequeo defensivo (validarMonto) en el propio GastoService. Y el rango de fechas
 * invertido en el LISTADO (a diferencia de los reportes, que sí lo rechazan explícitamente).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GastoService - Casos adversariales adicionales")
class GastoServiceAdversarialTest {

    @Mock private GastoRepository gastoRepository;
    @Mock private EstablecimientoRepository establecimientoRepository;
    @Mock private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock private TurnoCajaService turnoCajaService;
    @Mock private RegistroAuditoriaService registroAuditoriaService;

    private GastoService gastoService;
    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        gastoService = new GastoService(gastoRepository, establecimientoRepository, autorizacionEmpleadoService,
                new GastoMapper(), turnoCajaService, registroAuditoriaService);

        dueno = Usuario.builder().id(2L).email("dueno@test.com").nombre("Dueño Test").rol(Role.OWNER).build();
        establecimiento = Establecimiento.builder().id(10L).nombre("Establecimiento Test").dueno(dueno).build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
    }

    private GastoRequest requestConMonto(BigDecimal monto) {
        return new GastoRequest(LocalDate.now(), monto, CategoriaGasto.OTROS, "Gasto de prueba", MetodoPago.EFECTIVO, null);
    }

    @Test
    @DisplayName("registrarGasto: monto negativo se rechaza (defensa en el service, no solo en el DTO)")
    void registrarGasto_MontoNegativo_Rechaza() {
        assertThrows(IllegalArgumentException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), requestConMonto(BigDecimal.valueOf(-100)), dueno.getEmail()));
    }

    @Test
    @DisplayName("registrarGasto: monto cero se rechaza")
    void registrarGasto_MontoCero_Rechaza() {
        assertThrows(IllegalArgumentException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), requestConMonto(BigDecimal.ZERO), dueno.getEmail()));
    }

    @Test
    @DisplayName("registrarGasto: monto enorme no rompe nada (BigDecimal, sin overflow)")
    void registrarGasto_MontoEnorme_NoRompe() {
        BigDecimal montoEnorme = new BigDecimal("99999999999999999999999999999999999999.99"); // 38 dígitos, límite de NUMERIC(38,2)
        when(gastoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> gastoService.registrarGasto(establecimiento.getId(), requestConMonto(montoEnorme), dueno.getEmail()));
    }

    /**
     * FIX aplicado (ver REVISION_FUNCIONAL.md): GastoService.registrarGasto ahora re-valida
     * server-side que la descripción no esté vacía (validarCamposObligatorios), no solo el DTO
     * vía @NotBlank/@Valid en el controller. Antes de este fix, una descripción vacía se
     * persistía sin problema si algún caller se saltaba la validación del controller.
     */
    @Test
    @DisplayName("registrarGasto: descripción vacía se rechaza a nivel service (defensa en profundidad, no solo el DTO)")
    void registrarGasto_DescripcionVacia_SeRechazaEnElService() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.TEN, CategoriaGasto.OTROS, "", MetodoPago.EFECTIVO, null);

        assertThrows(IllegalArgumentException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), request, dueno.getEmail()));
    }

    /**
     * FIX aplicado (ver REVISION_FUNCIONAL.md): antes, un metodoPago nulo llegaba hasta
     * GastoMapper y reventaba con NullPointerException en vez de un error de negocio claro.
     * Ahora GastoService.registrarGasto rechaza explícitamente con IllegalArgumentException
     * antes de siquiera construir la entidad.
     */
    @Test
    @DisplayName("registrarGasto: método de pago nulo se rechaza a nivel service con un mensaje de negocio claro (ya no NPE)")
    void registrarGasto_MetodoPagoNulo_SeRechazaConMensajeDeNegocio() {
        GastoRequest request = new GastoRequest(LocalDate.now(), BigDecimal.TEN, CategoriaGasto.OTROS, "Gasto", null, null);

        assertThrows(IllegalArgumentException.class,
                () -> gastoService.registrarGasto(establecimiento.getId(), request, dueno.getEmail()));
    }

    /**
     * A diferencia de ReporteGastosService (que rechaza desde > hasta con
     * IllegalArgumentException), GastoService.listarGastos no valida el rango en absoluto:
     * lo pasa tal cual a GastoRepository.buscar, cuyo WHERE con "fecha >= :desde AND fecha
     * <= :hasta" simplemente no matchea nada si el rango está invertido. Resultado: página
     * vacía, sin error. Documentado como inconsistencia de API (un endpoint de listados
     * "traga" el rango invertido silenciosamente, el de reportes lo rechaza con 400) en
     * REVISION_FUNCIONAL.md, no como bug bloqueante.
     */
    @Test
    @DisplayName("listarGastos: rango invertido (desde > hasta) NO se rechaza, delega al repositorio tal cual")
    void listarGastos_RangoInvertido_NoSeRechazaDelegaAlRepositorio() {
        LocalDate desde = LocalDate.of(2026, 6, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 1);
        Pageable pageable = PageRequest.of(0, 10);
        Page<com.matiasmeira.sacaladelangulo.gastos.model.Gasto> paginaVacia = new PageImpl<>(List.of());
        when(gastoRepository.buscar(eq(establecimiento.getId()), eq(desde), eq(hasta), isNull(), eq(pageable)))
                .thenReturn(paginaVacia);

        assertDoesNotThrow(() -> gastoService.listarGastos(establecimiento.getId(), dueno.getEmail(), desde, hasta, null, pageable));
    }
}
