package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reportes.dto.CierreCajaReporteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteCierreCajaService")
class ReporteCierreCajaServiceTest {

    @Mock
    private TurnoCajaRepository turnoCajaRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    private ReporteCierreCajaService reporteCierreCajaService;

    @BeforeEach
    void setUp() {
        reporteCierreCajaService = new ReporteCierreCajaService(turnoCajaRepository, reporteAutorizacionService);
    }

    private TurnoCaja turnoCerrado(Long id, BigDecimal teorico, BigDecimal real, BigDecimal diferencia) {
        return TurnoCaja.builder()
                .id(id)
                .estado(EstadoTurnoCaja.CERRADO)
                .fechaApertura(LocalDateTime.of(2026, 1, 10, 8, 0))
                .fechaCierre(LocalDateTime.of(2026, 1, 10, 20, 0))
                .saldoTeoricoEfectivo(teorico)
                .saldoRealContado(real)
                .diferencia(diferencia)
                .build();
    }

    @Test
    @DisplayName("Acumula faltante y sobrante por separado a partir de las diferencias de varios turnos")
    void obtenerDiferenciasDeCaja_AcumulaFaltanteYSobranteCorrectamente() {
        Long establecimientoId = 10L;
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        List<TurnoCaja> turnos = List.of(
                turnoCerrado(1L, BigDecimal.valueOf(1000), BigDecimal.valueOf(950), BigDecimal.valueOf(-50)),
                turnoCerrado(2L, BigDecimal.valueOf(2000), BigDecimal.valueOf(2030), BigDecimal.valueOf(30)),
                turnoCerrado(3L, BigDecimal.valueOf(500), BigDecimal.valueOf(470), BigDecimal.valueOf(-30)),
                turnoCerrado(4L, BigDecimal.valueOf(800), BigDecimal.valueOf(800), BigDecimal.ZERO)
        );
        when(turnoCajaRepository.findByEstablecimientoIdAndEstadoAndFechaCierreBetween(
                eq(establecimientoId), eq(EstadoTurnoCaja.CERRADO), any(), any()))
                .thenReturn(turnos);

        CierreCajaReporteResponse response = reporteCierreCajaService.obtenerDiferenciasDeCaja(establecimientoId, desde, hasta, "dueno@test.com");

        assertEquals(4, response.turnos().size());
        assertEquals(0, BigDecimal.valueOf(80).compareTo(response.faltanteAcumulado()));
        assertEquals(0, BigDecimal.valueOf(30).compareTo(response.sobranteAcumulado()));

        var primero = response.turnos().stream().filter(t -> t.turnoCajaId().equals(1L)).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.valueOf(-50).compareTo(primero.diferencia()));
    }

    @Test
    @DisplayName("Devuelve listado vacío y acumulados en cero cuando no hay turnos cerrados en el rango")
    void obtenerDiferenciasDeCaja_SinTurnosCerrados_DevuelveVacioYAcumuladosEnCero() {
        Long establecimientoId = 10L;
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));
        when(turnoCajaRepository.findByEstablecimientoIdAndEstadoAndFechaCierreBetween(
                eq(establecimientoId), eq(EstadoTurnoCaja.CERRADO), any(), any()))
                .thenReturn(List.of());

        CierreCajaReporteResponse response = reporteCierreCajaService.obtenerDiferenciasDeCaja(establecimientoId, desde, hasta, "dueno@test.com");

        assertTrue(response.turnos().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.faltanteAcumulado()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.sobranteAcumulado()));
    }

    @Test
    @DisplayName("Rechaza desde posterior a hasta antes de consultar el repositorio")
    void obtenerDiferenciasDeCaja_Fallo_DesdePosteriorAHasta() {
        LocalDate desde = LocalDate.of(2026, 1, 10);
        LocalDate hasta = LocalDate.of(2026, 1, 1);

        assertThrows(IllegalArgumentException.class,
                () -> reporteCierreCajaService.obtenerDiferenciasDeCaja(10L, desde, hasta, "dueno@test.com"));
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerDiferenciasDeCaja_Fallo_NoEsDueno() {
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteCierreCajaService.obtenerDiferenciasDeCaja(10L, desde, hasta, "otro@test.com"));
    }
}
