package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reportes.dto.ClientesReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteClientesService")
class ReporteClientesServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    @InjectMocks
    private ReporteClientesService reporteClientesService;

    @Test
    @DisplayName("Cuenta como cliente nuevo solo al jugador cuya primera reserva histórica cae dentro del período, no antes")
    void obtenerClientes_ClientesNuevos_SoloPrimeraReservaEnElPeriodo() {
        Long establecimientoId = 10L;
        LocalDate desde = LocalDate.now().plusDays(30);
        LocalDate hasta = desde.plusDays(6);
        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        when(reservaRepository.primeraReservaPorJugador(establecimientoId)).thenReturn(List.<Object[]>of(
                new Object[]{1L, desde.plusDays(2).atTime(10, 0)},              // nuevo en el período actual
                new Object[]{2L, anterior.desde().plusDays(1).atTime(10, 0)},   // nuevo en el período anterior
                new Object[]{3L, anterior.desde().minusDays(10).atTime(10, 0)}  // cliente viejo, no cuenta en ninguno
        ));
        when(reservaRepository.topClientesPorReservas(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta)), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of());
        when(reservaRepository.countAusenciasEnRango(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(0L);

        ClientesReporteResponse response = reporteClientesService.obtenerClientes(establecimientoId, desde, hasta, 10, "dueno@test.com");

        assertEquals(1L, response.clientesNuevos().actual());
        assertEquals(1L, response.clientesNuevos().anterior());
    }

    @Test
    @DisplayName("Top clientes respeta el orden y el topN de la query")
    void obtenerClientes_TopClientes_RespetaOrdenYTopN() {
        Long establecimientoId = 10L;
        LocalDate desde = LocalDate.now().plusDays(30);
        LocalDate hasta = desde.plusDays(6);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));
        when(reservaRepository.primeraReservaPorJugador(establecimientoId)).thenReturn(List.of());
        when(reservaRepository.topClientesPorReservas(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta)), eq(PageRequest.of(0, 2))))
                .thenReturn(List.<Object[]>of(
                        new Object[]{1L, "Juan", 5L},
                        new Object[]{2L, "Pedro", 3L}
                ));
        when(reservaRepository.countAusenciasPorJugadoresEnRango(eq(establecimientoId), eq(List.of(1L, 2L)), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 2L}));
        when(reservaRepository.countAusenciasEnRango(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(0L);

        ClientesReporteResponse response = reporteClientesService.obtenerClientes(establecimientoId, desde, hasta, 2, "dueno@test.com");

        assertEquals(2, response.topClientes().size());
        assertEquals("Juan", response.topClientes().get(0).nombre());
        assertEquals(5L, response.topClientes().get(0).cantidadReservas());
        assertEquals(2L, response.topClientes().get(0).ausencias());
        assertEquals("Pedro", response.topClientes().get(1).nombre());
        assertEquals(0L, response.topClientes().get(1).ausencias());
    }

    @Test
    @DisplayName("Ausencias cuenta las reservas AUSENTE del establecimiento en el rango")
    void obtenerClientes_Ausencias_CuentaReservasAusentes() {
        Long establecimientoId = 10L;
        LocalDate desde = LocalDate.now().plusDays(30);
        LocalDate hasta = desde;

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));
        when(reservaRepository.primeraReservaPorJugador(establecimientoId)).thenReturn(List.of());
        when(reservaRepository.topClientesPorReservas(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta)), eq(PageRequest.of(0, 10))))
                .thenReturn(List.of());
        when(reservaRepository.countAusenciasEnRango(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(desde)), eq(PeriodoUtil.finDelDia(hasta))))
                .thenReturn(4L);

        ClientesReporteResponse response = reporteClientesService.obtenerClientes(establecimientoId, desde, hasta, 10, "dueno@test.com");

        assertTrue(response.ausencias().disponible());
        assertEquals(4L, response.ausencias().total());
        assertNull(response.ausencias().motivoNoDisponible());
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerClientes_Fallo_NoEsDueno() {
        LocalDate desde = LocalDate.now().plusDays(1);
        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteClientesService.obtenerClientes(10L, desde, desde, 10, "otro@test.com"));
    }
}
