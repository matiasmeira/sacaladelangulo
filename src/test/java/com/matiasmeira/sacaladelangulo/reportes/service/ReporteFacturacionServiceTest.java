package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reportes.dto.FacturacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reserva.model.MetodoPago;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteFacturacionService")
class ReporteFacturacionServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    @InjectMocks
    private ReporteFacturacionService reporteFacturacionService;

    private static LocalDate proximoLunes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.MONDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @Test
    @DisplayName("Calcula total facturado, desglose por método de pago y serie temporal alineada por período anterior")
    void obtenerFacturacion_CalculaTotalDesgloseYSerie() {
        Long establecimientoId = 10L;
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.plusDays(1);
        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        when(reservaRepository.sumFacturacionPorMetodoPago(
                eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(List.<Object[]>of(
                        new Object[]{MetodoPago.EFECTIVO, BigDecimal.valueOf(1000), 2L},
                        new Object[]{MetodoPago.MERCADO_PAGO, BigDecimal.valueOf(500), 1L}
                ));
        when(reservaRepository.sumFacturacionPorMetodoPago(
                eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(anterior.desde())), eq(PeriodoUtil.finDelDia(anterior.hasta()))))
                .thenReturn(List.<Object[]>of(
                        new Object[]{MetodoPago.EFECTIVO, BigDecimal.valueOf(300), 1L}
                ));

        when(reservaRepository.findFechaYPrecioParaSerieTemporal(
                eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(List.<Object[]>of(
                        new Object[]{desde.atTime(10, 0), BigDecimal.valueOf(1000)},
                        new Object[]{hasta.atTime(11, 0), BigDecimal.valueOf(500)}
                ));
        when(reservaRepository.findFechaYPrecioParaSerieTemporal(
                eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(anterior.desde())), eq(PeriodoUtil.finDelDia(anterior.hasta()))))
                .thenReturn(List.<Object[]>of(
                        new Object[]{anterior.desde().atTime(9, 0), BigDecimal.valueOf(300)}
                ));

        FacturacionReporteResponse response = reporteFacturacionService.obtenerFacturacion(establecimientoId, desde, hasta, "dueno@test.com");

        assertEquals(0, BigDecimal.valueOf(1500).compareTo(response.totalFacturado().actual()));
        assertEquals(0, BigDecimal.valueOf(300).compareTo(response.totalFacturado().anterior()));

        var efectivo = response.desglosePorMetodoPago().stream()
                .filter(d -> d.metodoPago() == MetodoPago.EFECTIVO).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(efectivo.monto().actual()));
        assertEquals(0, BigDecimal.valueOf(300).compareTo(efectivo.monto().anterior()));
        assertEquals(2L, efectivo.cantidadReservas().actual());
        assertEquals(1L, efectivo.cantidadReservas().anterior());

        var mercadoPago = response.desglosePorMetodoPago().stream()
                .filter(d -> d.metodoPago() == MetodoPago.MERCADO_PAGO).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.valueOf(500).compareTo(mercadoPago.monto().actual()));
        assertEquals(0, BigDecimal.ZERO.compareTo(mercadoPago.monto().anterior()));

        assertEquals(2, response.serieTemporal().size());
        var punto0 = response.serieTemporal().get(0);
        assertEquals(desde, punto0.fechaActual());
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(punto0.montoActual()));
        assertEquals(anterior.desde(), punto0.fechaAnterior());
        assertEquals(0, BigDecimal.valueOf(300).compareTo(punto0.montoAnterior()));

        var punto1 = response.serieTemporal().get(1);
        assertEquals(hasta, punto1.fechaActual());
        assertEquals(0, BigDecimal.valueOf(500).compareTo(punto1.montoActual()));
        assertEquals(0, BigDecimal.ZERO.compareTo(punto1.montoAnterior()));
    }

    @Test
    @DisplayName("Rechaza desde posterior a hasta antes de consultar el repositorio")
    void obtenerFacturacion_Fallo_DesdePosteriorAHasta() {
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde.minusDays(1);

        assertThrows(IllegalArgumentException.class,
                () -> reporteFacturacionService.obtenerFacturacion(10L, desde, hasta, "dueno@test.com"));
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerFacturacion_Fallo_NoEsDueno() {
        LocalDate desde = proximoLunes();
        LocalDate hasta = desde;

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteFacturacionService.obtenerFacturacion(10L, desde, hasta, "otro@test.com"));
    }
}
