package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.OcupacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaOcupacionProjection;
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
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReporteOcupacionService")
class ReporteOcupacionServiceTest {

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private ReporteAutorizacionService reporteAutorizacionService;

    @InjectMocks
    private ReporteOcupacionService reporteOcupacionService;

    private static LocalDate proximoLunes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.MONDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @Test
    @DisplayName("Calcula % de ocupación general combinando horario de atención, canchas activas y reservas FINALIZADA")
    void obtenerOcupacion_CalculaPorcentajeGeneral() {
        Long establecimientoId = 10L;
        LocalDate lunes = proximoLunes();

        Establecimiento establecimiento = Establecimiento.builder()
                .id(establecimientoId)
                .dueno(Usuario.builder().id(1L).build())
                .horariosAtencion(List.of(HorarioAtencion.builder()
                        .diaSemana(DayOfWeek.MONDAY)
                        .horaApertura(LocalTime.of(8, 0))
                        .horaCierre(LocalTime.of(22, 0))
                        .build()))
                .build();

        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, "dueno@test.com"))
                .thenReturn(establecimiento);
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId))
                .thenReturn(List.of(Cancha.builder().id(1L).nombre("Cancha 1").build()));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(eq(establecimientoId), any(), any()))
                .thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(establecimientoId), any(), any()))
                .thenReturn(List.of());

        ReservaOcupacionProjection reserva = mock(ReservaOcupacionProjection.class);
        when(reserva.getCanchaId()).thenReturn(1L);
        when(reserva.getFechaHoraInicio()).thenReturn(lunes.atTime(9, 0));
        when(reserva.getFechaHoraFin()).thenReturn(lunes.atTime(10, 0));

        when(reservaRepository.findProyeccionOcupacion(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(lunes)), eq(PeriodoUtil.finDelDia(lunes))))
                .thenReturn(List.of(reserva));
        when(reservaRepository.findProyeccionOcupacion(eq(establecimientoId), eq(PeriodoUtil.inicioDelDia(lunes.minusDays(1))), eq(PeriodoUtil.finDelDia(lunes.minusDays(1)))))
                .thenReturn(List.of());

        OcupacionReporteResponse response = reporteOcupacionService.obtenerOcupacion(establecimientoId, lunes, lunes, "dueno@test.com");

        // 1h reservada / 14h disponibles (08-22) = 7.14%
        assertEquals(0, new BigDecimal("7.14").compareTo(response.porcentajeOcupacionGeneral().actual()));
        // El día anterior es domingo, sin HorarioAtencion configurado -> 0 horas disponibles -> 0%
        assertEquals(0, BigDecimal.ZERO.compareTo(response.porcentajeOcupacionGeneral().anterior()));
        assertFalse(response.notaMetodologica().isBlank());
        assertEquals(1, response.ocupacionPorCancha().size());
        assertEquals("Cancha 1", response.ocupacionPorCancha().get(0).canchaNombre());
    }

    @Test
    @DisplayName("Propaga AccessDeniedException si el usuario no es dueño del establecimiento")
    void obtenerOcupacion_Fallo_NoEsDueno() {
        LocalDate lunes = proximoLunes();
        when(reporteAutorizacionService.validarDuenoDelEstablecimiento(10L, "otro@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> reporteOcupacionService.obtenerOcupacion(10L, lunes, lunes, "otro@test.com"));
    }
}
