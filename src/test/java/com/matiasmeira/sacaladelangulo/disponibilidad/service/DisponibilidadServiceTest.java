package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDiaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.SlotDisponibleResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DisponibilidadService - Tests de grilla de turnos disponibles")
class DisponibilidadServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private DisponibilidadService disponibilidadService;

    private Establecimiento establecimiento;
    private Cancha cancha;
    private LocalDate fecha;

    @BeforeEach
    void setUp() {
        fecha = LocalDate.now().plusDays(30);

        establecimiento = Establecimiento.builder()
                .id(100L)
                .nombre("Complejo Test")
                .horariosAtencion(new ArrayList<>(List.of(
                        HorarioAtencion.builder()
                                .diaSemana(fecha.getDayOfWeek())
                                .horaApertura(LocalTime.of(9, 0))
                                .horaCierre(LocalTime.of(11, 0))
                                .build()
                )))
                .build();

        cancha = Cancha.builder()
                .id(1L)
                .nombre("Cancha 1")
                .establecimiento(establecimiento)
                .deportes(Set.of(Deporte.FUTBOL))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("obtenerDisponibilidad lanza EntityNotFoundException si el establecimiento no existe")
    void lanzaExcepcionSiEstablecimientoNoExiste() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, null));
    }

    @Test
    @DisplayName("obtenerDisponibilidad lanza IllegalArgumentException si fechaFin es anterior a fecha")
    void lanzaExcepcionSiFechaFinEsAnterior() {
        assertThrows(IllegalArgumentException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, fecha.minusDays(1)));
    }

    @Test
    @DisplayName("obtenerDisponibilidad lanza IllegalArgumentException si el rango supera el máximo permitido")
    void lanzaExcepcionSiRangoSuperaElMaximo() {
        assertThrows(IllegalArgumentException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, fecha.plusDays(40)));
    }

    @Test
    @DisplayName("obtenerDisponibilidad marca el día cerrado si es día no laborable")
    void marcaDiaCerradoSiEsDiaNoLaborable() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha))
                .thenReturn(List.of(DiaNoLaborable.builder().fecha(fecha).motivo("Feriado").build()));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null);

        assertEquals(1, response.dias().size());
        DisponibilidadDiaResponse dia = response.dias().get(0);
        assertFalse(dia.abierto());
        assertEquals("Feriado", dia.motivoCierre());
        assertTrue(dia.canchas().isEmpty());
    }

    @Test
    @DisplayName("obtenerDisponibilidad marca el día cerrado si no hay horario de atención configurado")
    void marcaDiaCerradoSiNoHayHorarioAtencion() {
        establecimiento.setHorariosAtencion(new ArrayList<>());
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null);

        assertFalse(response.dias().get(0).abierto());
    }

    @Test
    @DisplayName("obtenerDisponibilidad genera slots libres respetando duración y granularidad horaria")
    void generaSlotsLibresRespetandoDuracionYGranularidad() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null);

        DisponibilidadCanchaResponse canchaResponse = response.dias().get(0).canchas().get(0);
        List<SlotDisponibleResponse> slots = canchaResponse.opcionesDuracion().get(0).slotsLibres();

        assertEquals(2, slots.size());
        assertEquals(LocalDateTime.of(fecha, LocalTime.of(9, 0)), slots.get(0).inicio());
        assertEquals(LocalDateTime.of(fecha, LocalTime.of(10, 0)), slots.get(1).inicio());
    }

    @Test
    @DisplayName("obtenerDisponibilidad excluye slots ocupados por una reserva existente")
    void excluyeSlotsOcupadosPorReserva() {
        Reserva reservaExistente = Reserva.builder()
                .id(500L)
                .cancha(cancha)
                .estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(LocalDateTime.of(fecha, LocalTime.of(9, 0)))
                .fechaHoraFin(LocalDateTime.of(fecha, LocalTime.of(10, 0)))
                .build();

        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of(reservaExistente));

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null);

        List<SlotDisponibleResponse> slots = response.dias().get(0).canchas().get(0).opcionesDuracion().get(0).slotsLibres();
        assertEquals(1, slots.size());
        assertEquals(LocalDateTime.of(fecha, LocalTime.of(10, 0)), slots.get(0).inicio());
    }

    @Test
    @DisplayName("obtenerDisponibilidad excluye slots ocupados por un bloqueo de cancha")
    void excluyeSlotsOcupadosPorBloqueo() {
        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(fecha, LocalTime.of(10, 0)))
                .fechaFin(LocalDateTime.of(fecha, LocalTime.of(11, 0)))
                .motivo("Mantenimiento")
                .build();

        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of(bloqueo));
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null);

        List<SlotDisponibleResponse> slots = response.dias().get(0).canchas().get(0).opcionesDuracion().get(0).slotsLibres();
        assertEquals(1, slots.size());
        assertEquals(LocalDateTime.of(fecha, LocalTime.of(9, 0)), slots.get(0).inicio());
    }
}
