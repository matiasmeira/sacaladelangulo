package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reportes.dto.HorariosPedidosReporteResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteHorariosService")
class ReporteHorariosServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    @InjectMocks
    private ReporteHorariosService reporteHorariosService;

    private static LocalDate proximoMartes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.TUESDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @Test
    @DisplayName("Agrupa por día de semana + hora y ordena descendente por cantidad")
    void obtenerHorariosPedidos_AgrupaYOrdenaDescendente() {
        Long establecimientoId = 10L;
        LocalDate martes = proximoMartes();
        LocalDate domingo = martes.plusDays(5);

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));

        when(reservaRepository.findFechasParaHorariosPedidos(eq(establecimientoId), any(), any()))
                .thenReturn(List.of(
                        martes.atTime(20, 15),
                        martes.atTime(20, 45),
                        martes.atTime(20, 5),
                        martes.atTime(21, 0),
                        domingo.atTime(10, 30)
                ));

        HorariosPedidosReporteResponse response = reporteHorariosService.obtenerHorariosPedidos(establecimientoId, martes, domingo, 10, "dueno@test.com");

        assertEquals(3, response.ranking().size());
        assertEquals(DayOfWeek.TUESDAY, response.ranking().get(0).diaSemana());
        assertEquals(20, response.ranking().get(0).hora());
        assertEquals(3L, response.ranking().get(0).cantidadReservas());
    }

    @Test
    @DisplayName("Aplica topN como límite de resultados")
    void obtenerHorariosPedidos_AplicaTopN() {
        Long establecimientoId = 10L;
        LocalDate martes = proximoMartes();

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(mock(Establecimiento.class));
        when(reservaRepository.findFechasParaHorariosPedidos(eq(establecimientoId), any(), any()))
                .thenReturn(List.of(
                        martes.atTime(9, 0),
                        martes.atTime(10, 0),
                        martes.atTime(11, 0)
                ));

        HorariosPedidosReporteResponse response = reporteHorariosService.obtenerHorariosPedidos(establecimientoId, martes, martes, 2, "dueno@test.com");

        assertEquals(2, response.ranking().size());
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerHorariosPedidos_Fallo_NoEsDueno() {
        LocalDate desde = LocalDate.now().plusDays(1);
        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteHorariosService.obtenerHorariosPedidos(10L, desde, desde, 10, "otro@test.com"));
    }
}
