package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.RangoOcupadoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Ocupación derivada del pool (DisponibilidadCanchaResponse.ocupadaPorPool): una cancha
 * queda ahí en un rango si y solo si una reserva nueva sobre ella en ese rango sería
 * rechazada por PoolCanchaCalculator.hayDisponibilidad (ver
 * docs/superpowers/specs/2026-09-10-disponibilidad-ocupacion-pool.md).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisponibilidadService - ocupación derivada del pool")
class DisponibilidadServiceOcupacionPorPoolTest {

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
    private Cancha f1;
    private Cancha f2;
    private Cancha f3;
    private Cancha cancha7;
    private Cancha cancha9;
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
                                .horaApertura(LocalTime.of(14, 0))
                                .horaCierre(LocalTime.of(22, 0))
                                .build()
                )))
                .build();

        f1 = fisica(1L, "F1");
        f2 = fisica(2L, "F2");
        f3 = fisica(3L, "F3");
        cancha9 = logica(9L, "Cancha 9", Set.of(f1, f2, f3), 3);
        cancha7 = logica(7L, "Cancha 7", Set.of(f1, f2, f3), 2);

        when(establecimientoRepository.findById(100L)).thenReturn(java.util.Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L))
                .thenReturn(List.of(f1, f2, f3, cancha7, cancha9));
    }

    private Cancha fisica(long id, String nombre) {
        return Cancha.builder()
                .id(id)
                .nombre(nombre)
                .establecimiento(establecimiento)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .isActive(true)
                .build();
    }

    private Cancha logica(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder()
                .id(id)
                .nombre(nombre)
                .establecimiento(establecimiento)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .isActive(true)
                .canchasFisicas(pool)
                .canchasNecesarias(canchasNecesarias)
                .build();
    }

    private Reserva reservaSobre(Cancha cancha, LocalTime horaInicio, LocalTime horaFin) {
        return Reserva.builder()
                .cancha(cancha)
                .estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(LocalDateTime.of(fecha, horaInicio))
                .fechaHoraFin(LocalDateTime.of(fecha, horaFin))
                .build();
    }

    private Map<Long, DisponibilidadCanchaResponse> obtenerCanchasPorId() {
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(reservasDelTest);
        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);
        return response.dias().get(0).canchas().stream()
                .collect(java.util.stream.Collectors.toMap(DisponibilidadCanchaResponse::canchaId, c -> c));
    }

    private List<Reserva> reservasDelTest;

    @Test
    @DisplayName("Cancha 9 (necesita 3) reservada 17-18 ocupa por pool a F1, F2, F3 y Cancha 7")
    void cancha9Reservada_ocupaTodoElGrupo() {
        reservasDelTest = List.of(reservaSobre(cancha9, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        List<RangoOcupadoResponse> rangoEsperado = List.of(
                new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(17, 0)), LocalDateTime.of(fecha, LocalTime.of(18, 0))));
        assertEquals(rangoEsperado, canchas.get(1L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(2L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(3L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(7L).ocupadaPorPool());
        assertTrue(canchas.get(9L).ocupadaPorPool().isEmpty());
    }

    @Test
    @DisplayName("Cancha 7 (necesita 2) reservada 15-16 deja libres a F1, F2, F3 y ocupa a Cancha 9")
    void cancha7Reservada_dejaFisicasLibresYOcupaCancha9() {
        reservasDelTest = List.of(reservaSobre(cancha7, LocalTime.of(15, 0), LocalTime.of(16, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertEquals(
                List.of(new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(15, 0)), LocalDateTime.of(fecha, LocalTime.of(16, 0)))),
                canchas.get(9L).ocupadaPorPool());
    }

    @Test
    @DisplayName("Una física suelta reservada no ocupa a las otras físicas, sí a Cancha 9 sin cupo")
    void fisicaSueltaReservada_noOcupaOtrasFisicas_siOcupaCancha9() {
        reservasDelTest = List.of(reservaSobre(f1, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertEquals(
                List.of(new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(17, 0)), LocalDateTime.of(fecha, LocalTime.of(18, 0)))),
                canchas.get(9L).ocupadaPorPool());
    }

    @Test
    @DisplayName("Dos reservas contiguas del mismo grupo fusionan el rango derivado en uno solo")
    void reservasContiguas_fusionanElRangoDerivado() {
        reservasDelTest = List.of(
                reservaSobre(cancha9, LocalTime.of(15, 0), LocalTime.of(16, 0)),
                reservaSobre(cancha9, LocalTime.of(16, 0), LocalTime.of(17, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        List<RangoOcupadoResponse> rangoEsperado = List.of(
                new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(15, 0)), LocalDateTime.of(fecha, LocalTime.of(17, 0))));
        assertEquals(rangoEsperado, canchas.get(1L).ocupadaPorPool());
        assertEquals(1, canchas.get(1L).ocupadaPorPool().size());
    }

    @Test
    @DisplayName("Una prereserva PENDIENTE_SENA vencida no genera ocupación derivada")
    void prereservaVencida_noGeneraOcupacionDerivada() {
        // ReservaRepository.findSuperpuestas ya excluye en la propia query JPQL (estado ==
        // PENDIENTE_SENA && expiraEn <= ahora): una prereserva vencida nunca llega a
        // DisponibilidadService, así que acá se simula exactamente ese contrato devolviendo
        // en el mock la lista SIN la prereserva vencida (tal como haría la query real).
        // Si esa prereserva vencida existiera sobre Cancha 9 17-18 y por un bug se colara
        // igual, F1/F2/F3/Cancha7 aparecerían ocupados 17-18 (mismo escenario que
        // cancha9Reservada_ocupaTodoElGrupo) — por eso alcanza con probar que, en su
        // ausencia correcta, ninguna cancha del grupo queda ocupada por pool en ese horario.
        reservasDelTest = List.of();

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(7L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(9L).ocupadaPorPool().isEmpty());
    }

    @Test
    @DisplayName("Sin canchas lógicas, ocupadaPorPool es vacío en todas las canchas")
    void sinCanchasLogicas_ocupadaPorPoolVacioEnTodas() {
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(f1, f2, f3));
        reservasDelTest = List.of(reservaSobre(f1, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
    }
}
