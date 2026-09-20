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
import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoOperativoGuard;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Mock
    private EstablecimientoOperativoGuard establecimientoOperativoGuard;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true));
    }

    /**
     * Camino del jugador (por id directo, sin pasar por el buscador público): revalida
     * EstablecimientoOperativoGuard igual que si no existiera, para no distinguir "no existe"
     * de "está deshabilitado" -- ver EstablecimientoOperativoGuard.
     */
    @Test
    @DisplayName("obtenerDisponibilidad lanza EntityNotFoundException si el establecimiento está deshabilitado")
    void lanzaExcepcionSiEstablecimientoEstaDeshabilitado() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        org.mockito.Mockito.doThrow(new EntityNotFoundException("Establecimiento no encontrado"))
                .when(establecimientoOperativoGuard).validarEstablecimientoOperativoParaJugador(establecimiento);

        assertThrows(EntityNotFoundException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true));
    }

    /**
     * Único endpoint real (DisponibilidadController) llama siempre a
     * obtenerDisponibilidadParaPanel, para PLAYER incluido: acá es donde el gate tiene que
     * correr de verdad. Sin acceso de panel a ESTE establecimiento, un caller es
     * indistinguible de un jugador cualquiera.
     */
    @Test
    @DisplayName("obtenerDisponibilidadParaPanel lanza EntityNotFoundException si el caller NO tiene acceso de panel y el establecimiento está deshabilitado")
    void obtenerDisponibilidadParaPanel_SinAccesoDePanel_EstablecimientoDeshabilitado_Lanza() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.tieneAccesoDePanel(eq(establecimiento), eq("jugador@test.com"), any()))
                .thenReturn(false);
        org.mockito.Mockito.doThrow(new EntityNotFoundException("Establecimiento no encontrado"))
                .when(establecimientoOperativoGuard).validarEstablecimientoOperativoParaJugador(establecimiento);

        assertThrows(EntityNotFoundException.class,
                () -> disponibilidadService.obtenerDisponibilidadParaPanel(100L, fecha, null, "jugador@test.com"));
    }

    /**
     * El dueño (o admin, o empleado con permiso) de ESTE establecimiento no debe perder
     * acceso a su propia agenda por estar deshabilitado: el gate no debe correr cuando
     * tieneAccesoDePanel da true, sin importar isActive.
     */
    @Test
    @DisplayName("obtenerDisponibilidadParaPanel NO lanza si el caller SÍ tiene acceso de panel, aunque el establecimiento esté deshabilitado")
    void obtenerDisponibilidadParaPanel_ConAccesoDePanel_EstablecimientoDeshabilitado_NoLanza() {
        establecimiento.setIsActive(false);
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(canchaRepository.findByEstablecimientoId(100L)).thenReturn(List.of(cancha));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());
        when(autorizacionEmpleadoService.tieneAccesoDePanel(eq(establecimiento), eq("dueno@test.com"), any()))
                .thenReturn(true);

        DisponibilidadEstablecimientoResponse response = assertDoesNotThrow(
                () -> disponibilidadService.obtenerDisponibilidadParaPanel(100L, fecha, null, "dueno@test.com"));

        assertTrue(response.dias().get(0).canchas().get(0).ocupadaPorPool() != null,
                "el dueño de su propio establecimiento sigue viendo ocupadaPorPool aunque esté deshabilitado");
        org.mockito.Mockito.verify(establecimientoOperativoGuard, org.mockito.Mockito.never())
                .validarEstablecimientoOperativoParaJugador(any());
    }

    /**
     * PERMISOS_OPERATIVOS_DE_RESERVA (el set que puebla ocupadaPorPool) es un subconjunto
     * angosto de PermisoEmpleado -- OPERAR_CAJA no está ahí. Este test prueba justamente que
     * el gate y ocupadaPorPool se calculan por separado: el empleado pertenece al
     * establecimiento (gate no corre) pero no ve ocupadaPorPool (permiso más angosto).
     */
    @Test
    @DisplayName("obtenerDisponibilidadParaPanel NO lanza para un EMPLOYEE con permiso no-operativo, y ocupadaPorPool queda en null")
    void obtenerDisponibilidadParaPanel_EmpleadoConPermisoNoOperativo_PerteneceAlEstablecimiento_NoLanzaYSinOcupacionPool() {
        establecimiento.setIsActive(false);
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(canchaRepository.findByEstablecimientoId(100L)).thenReturn(List.of(cancha));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(any(), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(any(), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(any(), any(), any(), any())).thenReturn(List.of());

        when(autorizacionEmpleadoService.tieneAccesoDePanel(
                eq(establecimiento), eq("empleado-caja@test.com"), eq(EnumSet.allOf(PermisoEmpleado.class))))
                .thenReturn(true);
        when(autorizacionEmpleadoService.tieneAccesoDePanel(
                eq(establecimiento), eq("empleado-caja@test.com"), eq(AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA)))
                .thenReturn(false);

        DisponibilidadEstablecimientoResponse response = assertDoesNotThrow(
                () -> disponibilidadService.obtenerDisponibilidadParaPanel(100L, fecha, null, "empleado-caja@test.com"));

        assertTrue(response.dias().get(0).canchas().get(0).ocupadaPorPool() == null,
                "sin permiso operativo de reserva, ocupadaPorPool sigue en null aunque pertenezca al establecimiento");
        org.mockito.Mockito.verify(establecimientoOperativoGuard, org.mockito.Mockito.never())
                .validarEstablecimientoOperativoParaJugador(any());
    }

    @Test
    @DisplayName("obtenerDisponibilidad lanza IllegalArgumentException si fechaFin es anterior a fecha")
    void lanzaExcepcionSiFechaFinEsAnterior() {
        assertThrows(IllegalArgumentException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, fecha.minusDays(1), true));
    }

    @Test
    @DisplayName("obtenerDisponibilidad lanza IllegalArgumentException si el rango supera el máximo permitido")
    void lanzaExcepcionSiRangoSuperaElMaximo() {
        assertThrows(IllegalArgumentException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, fecha.plusDays(40), true));
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

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

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

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

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

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

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

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

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

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

        List<SlotDisponibleResponse> slots = response.dias().get(0).canchas().get(0).opcionesDuracion().get(0).slotsLibres();
        assertEquals(1, slots.size());
        assertEquals(LocalDateTime.of(fecha, LocalTime.of(9, 0)), slots.get(0).inicio());
    }

    @Test
    @DisplayName("obtenerDisponibilidad trae ocupadaPorPool en null cuando el flag es false")
    void noIncluyeOcupacionPorPoolCuandoFlagEsFalse() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, false);

        DisponibilidadCanchaResponse canchaResponse = response.dias().get(0).canchas().get(0);
        assertEquals(null, canchaResponse.ocupadaPorPool());
    }

    @Test
    @DisplayName("obtenerDisponibilidad trae ocupadaPorPool como lista (no null) cuando el flag es true")
    void incluyeOcupacionPorPoolComoListaCuandoFlagEsTrue() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

        DisponibilidadCanchaResponse canchaResponse = response.dias().get(0).canchas().get(0);
        assertEquals(List.of(), canchaResponse.ocupadaPorPool());
    }
}
