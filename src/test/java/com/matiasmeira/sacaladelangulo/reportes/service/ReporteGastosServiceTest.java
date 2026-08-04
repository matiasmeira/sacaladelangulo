package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.FacturacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.GastosReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reportes.dto.ResultadoReporteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteGastosService")
class ReporteGastosServiceTest {

    @Mock
    private GastoRepository gastoRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    @Mock
    private ReporteFacturacionService reporteFacturacionService;

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
    @DisplayName("Calcula total gastado, desglose por categoría y serie temporal alineada por período anterior")
    void obtenerGastos_CalculaTotalDesgloseYSerie() {
        Long establecimientoId = 10L;
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.plusDays(1);
        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        when(gastoRepository.sumGastoPorCategoria(establecimientoId, desde, hasta)).thenReturn(List.<Object[]>of(
                new Object[]{CategoriaGasto.ALQUILER, BigDecimal.valueOf(1000), 2L},
                new Object[]{CategoriaGasto.SERVICIOS, BigDecimal.valueOf(500), 1L}
        ));
        when(gastoRepository.sumGastoPorCategoria(establecimientoId, anterior.desde(), anterior.hasta())).thenReturn(List.<Object[]>of(
                new Object[]{CategoriaGasto.ALQUILER, BigDecimal.valueOf(300), 1L}
        ));

        when(gastoRepository.findFechaYMontoParaSerieTemporal(establecimientoId, desde, hasta)).thenReturn(List.<Object[]>of(
                new Object[]{desde, BigDecimal.valueOf(1000)},
                new Object[]{hasta, BigDecimal.valueOf(500)}
        ));
        when(gastoRepository.findFechaYMontoParaSerieTemporal(establecimientoId, anterior.desde(), anterior.hasta())).thenReturn(List.<Object[]>of(
                new Object[]{anterior.desde(), BigDecimal.valueOf(300)}
        ));

        GastosReporteResponse response = reporteGastosService.obtenerGastos(establecimientoId, desde, hasta, "dueno@test.com");

        assertEquals(0, BigDecimal.valueOf(1500).compareTo(response.totalGastado().actual()));
        assertEquals(0, BigDecimal.valueOf(300).compareTo(response.totalGastado().anterior()));

        var alquiler = response.desglosePorCategoria().stream()
                .filter(d -> d.categoria() == CategoriaGasto.ALQUILER).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(alquiler.monto().actual()));
        assertEquals(0, BigDecimal.valueOf(300).compareTo(alquiler.monto().anterior()));
        assertEquals(2L, alquiler.cantidad().actual());
        assertEquals(1L, alquiler.cantidad().anterior());

        assertEquals(2, response.serieTemporal().size());
        var punto0 = response.serieTemporal().get(0);
        assertEquals(desde, punto0.fechaActual());
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(punto0.montoActual()));
        assertEquals(anterior.desde(), punto0.fechaAnterior());
        assertEquals(0, BigDecimal.valueOf(300).compareTo(punto0.montoAnterior()));
    }

    @Test
    @DisplayName("Rechaza desde posterior a hasta antes de consultar el repositorio")
    void obtenerGastos_Fallo_DesdePosteriorAHasta() {
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.minusDays(1);

        assertThrows(IllegalArgumentException.class,
                () -> reporteGastosService.obtenerGastos(10L, desde, hasta, "dueno@test.com"));
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerGastos_Fallo_NoEsDueno() {
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde;

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteGastosService.obtenerGastos(10L, desde, hasta, "otro@test.com"));
    }

    @Test
    @DisplayName("obtenerResultado calcula neto = facturado - gastos, actual y anterior")
    void obtenerResultado_CalculaNetoCorrectamente() {
        Long establecimientoId = 10L;
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.plusDays(1);
        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        Comparativo<BigDecimal> totalFacturado = new Comparativo<>(BigDecimal.valueOf(5000), BigDecimal.valueOf(4000));
        FacturacionReporteResponse facturacionResponse = new FacturacionReporteResponse(
                new RangoFechas(desde, hasta), anterior, totalFacturado, List.of(), List.of());
        when(reporteFacturacionService.obtenerFacturacion(establecimientoId, desde, hasta, "dueno@test.com"))
                .thenReturn(facturacionResponse);

        when(gastoRepository.sumGastoPorCategoria(eq(establecimientoId), any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{CategoriaGasto.ALQUILER, BigDecimal.valueOf(1200), 1L}
        ));

        ResultadoReporteResponse response = reporteGastosService.obtenerResultado(establecimientoId, desde, hasta, "dueno@test.com");

        assertEquals(0, BigDecimal.valueOf(5000).compareTo(response.totalFacturado().actual()));
        assertEquals(0, BigDecimal.valueOf(1200).compareTo(response.totalGastos().actual()));
        assertEquals(0, BigDecimal.valueOf(3800).compareTo(response.neto().actual()));
        assertEquals(0, BigDecimal.valueOf(2800).compareTo(response.neto().anterior()));
    }
}
