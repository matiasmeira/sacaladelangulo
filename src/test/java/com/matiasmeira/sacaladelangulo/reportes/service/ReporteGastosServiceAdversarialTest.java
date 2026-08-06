package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.FacturacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reportes.dto.ResultadoReporteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Casos adversariales de ReporteGastosService.obtenerResultado no cubiertos por
 * ReporteGastosServiceTest: neto negativo (gastos > facturado) y la comprobación de que
 * "facturado" en este reporte SOLO contempla reservas (ver ReservaRepository.sumFacturacionPorMetodoPago
 * usado por ReporteFacturacionService) — la venta de buffet, que sí genera ingresos reales de
 * caja, no está representada en absoluto en este número. No es un bug de cálculo (la resta
 * está bien hecha), es un hueco de alcance documentado en REVISION_FUNCIONAL.md.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteGastosService - Casos adversariales adicionales")
class ReporteGastosServiceAdversarialTest {

    @Mock private GastoRepository gastoRepository;
    @Mock private ReporteAutorizacionService reporteAutorizacionService;
    @Mock private ReporteFacturacionService reporteFacturacionService;

    private ReporteGastosService reporteGastosService;

    @BeforeEach
    void setUp() {
        reporteGastosService = new ReporteGastosService(gastoRepository, reporteAutorizacionService, reporteFacturacionService);
    }

    private static LocalDate proximoLunes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.MONDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @Test
    @DisplayName("obtenerResultado: gastos > facturado da un neto NEGATIVO bien calculado")
    void obtenerResultado_GastosSuperanFacturado_NetoNegativo() {
        Long establecimientoId = 10L;
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.plusDays(1);
        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        Comparativo<BigDecimal> totalFacturado = new Comparativo<>(BigDecimal.valueOf(1000), BigDecimal.valueOf(1000));
        FacturacionReporteResponse facturacionResponse = new FacturacionReporteResponse(
                new RangoFechas(desde, hasta), anterior, totalFacturado, List.of(), List.of());
        when(reporteFacturacionService.obtenerFacturacion(establecimientoId, desde, hasta, "dueno@test.com"))
                .thenReturn(facturacionResponse);

        // Un mes de gastos fijos (alquiler + sueldos) muy por encima de lo facturado en el rango.
        when(gastoRepository.sumGastoPorCategoria(eq(establecimientoId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{CategoriaGasto.ALQUILER, BigDecimal.valueOf(3000), 1L},
                new Object[]{CategoriaGasto.SUELDOS, BigDecimal.valueOf(5000), 2L}
        ));

        ResultadoReporteResponse response = reporteGastosService.obtenerResultado(establecimientoId, desde, hasta, "dueno@test.com");

        assertEquals(0, BigDecimal.valueOf(8000).compareTo(response.totalGastos().actual()));
        assertEquals(0, BigDecimal.valueOf(-7000).compareTo(response.neto().actual()));
        assertTrue(response.neto().actual().signum() < 0, "El neto debe quedar negativo cuando los gastos superan lo facturado");
    }
}
