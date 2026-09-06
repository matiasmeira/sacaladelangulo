package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests adversariales de solapamiento parcial, movimiento de reserva a un slot ocupado,
 * bordes de fecha/duración inválidos, y la garantía de que el precio SIEMPRE se recalcula
 * en el servidor (nunca se confía en un valor mandado por el cliente).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservaService - Solapamiento parcial, movimiento y dinero recalculado en servidor")
class ReservaServiceSolapamientoYDineroAdversarialTest {

    @Mock private ReservaRepository reservaRepository;
    @Mock private CanchaRepository canchaRepository;
    @Mock private BloqueoCanchaRepository bloqueoCanchaRepository;
    @Mock private BloqueoJugadorRepository bloqueoJugadorRepository;
    @Mock private DiaNoLaborableRepository diaNoLaborableRepository;
    @Mock private EstablecimientoRepository establecimientoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ReservaMapper reservaMapper;
    @Mock private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock private RegistroAuditoriaService registroAuditoriaService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TurnoCajaService turnoCajaService;

    private ReservaService reservaService;

    private Usuario jugador;
    private Usuario dueno;
    private Establecimiento establecimiento;
    private Cancha cancha;
    private Cancha canchaDestino;

    private static final LocalDate FECHA_BASE = proximoMartes();

    private static LocalDate proximoMartes() {
        LocalDate fecha = LocalDate.now().plusDays(1);
        while (fecha.getDayOfWeek() != DayOfWeek.TUESDAY) {
            fecha = fecha.plusDays(1);
        }
        return fecha;
    }

    @BeforeEach
    void setUp() {
        reservaService = new ReservaService(reservaRepository, canchaRepository, bloqueoCanchaRepository,
                bloqueoJugadorRepository, diaNoLaborableRepository, establecimientoRepository, usuarioRepository,
                reservaMapper, autorizacionEmpleadoService, registroAuditoriaService, eventPublisher, turnoCajaService);

        jugador = Usuario.builder().id(1L).email("jugador@test.com").password("x").nombre("Juan")
                .rol(Role.PLAYER).planSuscripcion(PlanSuscripcion.FREE).isActive(true)
                .emailVerified(true).telefonoVerificado(false).build();

        dueno = Usuario.builder().id(2L).email("dueno@test.com").password("x").nombre("Carlos")
                .rol(Role.OWNER).planSuscripcion(PlanSuscripcion.PREMIUM).isActive(true)
                .emailVerified(true).telefonoVerificado(false).build();

        establecimiento = Establecimiento.builder().id(10L).nombre("Club").direccion("Calle 1")
                .latitud(0.0).longitud(0.0).dueno(dueno).requiereSena(false).isActive(true).build();
        establecimiento.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.TUESDAY).horaApertura(LocalTime.of(8, 0)).horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento).build()));

        cancha = Cancha.builder().id(100L).nombre("Cancha A").deportes(java.util.Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.ZERO)
                .duracionesPermitidas(new ArrayList<>(List.of(60))).permiteInicioMediaHora(true)
                .establecimiento(establecimiento).isActive(true).tarifas(new ArrayList<>()).canchasFisicas(new ArrayList<>())
                .build();

        canchaDestino = Cancha.builder().id(101L).nombre("Cancha B").deportes(java.util.Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.ZERO)
                .duracionesPermitidas(new ArrayList<>(List.of(60))).permiteInicioMediaHora(true)
                .establecimiento(establecimiento).isActive(true).tarifas(new ArrayList<>()).canchasFisicas(new ArrayList<>())
                .build();

        lenient().when(usuarioRepository.findByEmail(jugador.getEmail())).thenReturn(Optional.of(jugador));
        lenient().when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        lenient().when(canchaRepository.findById(cancha.getId())).thenReturn(Optional.of(cancha));
        lenient().when(canchaRepository.findById(canchaDestino.getId())).thenReturn(Optional.of(canchaDestino));
        lenient().when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId()))
                .thenReturn(List.of(cancha, canchaDestino));
        lenient().when(bloqueoJugadorRepository.existsByEstablecimientoIdAndJugadorId(any(), any())).thenReturn(false);
        lenient().when(reservaRepository.save(any(Reserva.class))).thenAnswer(inv -> {
            Reserva r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(999L);
            }
            return r;
        });
        lenient().when(reservaMapper.mapToResponse(any(Reserva.class))).thenAnswer(inv -> {
            Reserva r = inv.getArgument(0);
            return new ReservaResponse(r.getId(), null, null, r.getCancha().getId(), r.getCancha().getNombre(),
                    r.getFechaHoraInicio(), r.getFechaHoraFin(), r.getEstado().name(), r.getPrecioTotal(),
                    r.getSenaPagada(), null, null, r.getDeporteSeleccionado(), r.getExpiraEn(), null, null);
        });
    }

    private Reserva reservaExistente(Cancha c, LocalDateTime inicio, LocalDateTime fin) {
        return Reserva.builder().id(500L).cancha(c).jugador(jugador).fechaHoraInicio(inicio).fechaHoraFin(fin)
                .estado(EstadoReserva.CONFIRMADA).precioTotal(BigDecimal.valueOf(1000)).senaPagada(BigDecimal.ZERO).build();
    }

    @Test
    @DisplayName("Solapamiento PARCIAL en la misma cancha (18-19 existente vs 18:30-19:30 nueva) se rechaza")
    void crearReserva_SolapamientoParcial_Rechaza() {
        LocalDateTime existenteInicio = FECHA_BASE.atTime(18, 0);
        LocalDateTime existenteFin = FECHA_BASE.atTime(19, 0);
        LocalDateTime nuevaInicio = FECHA_BASE.atTime(18, 30);
        LocalDateTime nuevaFin = FECHA_BASE.atTime(19, 30);

        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(nuevaInicio), eq(nuevaFin), any()))
                .thenReturn(List.of(reservaExistente(cancha, existenteInicio, existenteFin)));

        ReservaRequest request = new ReservaRequest(cancha.getId(), nuevaInicio, nuevaFin, Deporte.FUTBOL_5);

        assertThrows(IllegalArgumentException.class, () -> reservaService.crearReserva(request, jugador.getEmail()));
    }

    @Test
    @DisplayName("moverReservaDeCancha a una cancha/horario ya ocupado se rechaza")
    void moverReservaDeCancha_DestinoOcupado_Rechaza() {
        LocalDateTime inicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fin = FECHA_BASE.atTime(11, 0);

        Reserva reservaAMover = Reserva.builder().id(1L).cancha(cancha).jugador(jugador)
                .fechaHoraInicio(inicio).fechaHoraFin(fin).estado(EstadoReserva.CONFIRMADA)
                .deporteSeleccionado(Deporte.FUTBOL_5).precioTotal(BigDecimal.valueOf(1000)).senaPagada(BigDecimal.ZERO).build();
        when(reservaRepository.findByIdConEstablecimientoYDueno(1L)).thenReturn(Optional.of(reservaAMover));

        // La cancha destino ya tiene otra reserva ocupando exactamente ese horario.
        Reserva ocupanteDestino = reservaExistente(canchaDestino, inicio, fin);
        ocupanteDestino.setId(600L);
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(inicio), eq(fin), any()))
                .thenReturn(List.of(ocupanteDestino));

        assertThrows(IllegalArgumentException.class,
                () -> reservaService.moverReservaDeCancha(1L, canchaDestino.getId(), dueno.getEmail()));
    }

    @Test
    @DisplayName("horaFin <= horaInicio (incluye duración cero) se rechaza")
    void crearReserva_HoraFinNoPosteriorAHoraInicio_Rechaza() {
        LocalDateTime inicio = FECHA_BASE.atTime(10, 0);

        // horaFin == horaInicio (duración cero) y horaFin < horaInicio caen en la MISMA
        // validación (validarFechas: !inicio.isBefore(fin)), así que un solo caso alcanza
        // para cubrir ambos enunciados del checklist: no hay un camino de código separado
        // para "duración cero" que la duración cero no dispare primero.
        ReservaRequest duracionCero = new ReservaRequest(cancha.getId(), inicio, inicio, Deporte.FUTBOL_5);
        assertThrows(IllegalArgumentException.class, () -> reservaService.crearReserva(duracionCero, jugador.getEmail()));

        ReservaRequest finAntesQueInicio = new ReservaRequest(cancha.getId(), inicio, inicio.minusMinutes(30), Deporte.FUTBOL_5);
        assertThrows(IllegalArgumentException.class, () -> reservaService.crearReserva(finAntesQueInicio, jugador.getEmail()));
    }

    @Test
    @DisplayName("Reserva en el pasado se rechaza")
    void crearReserva_FechaEnElPasado_Rechaza() {
        LocalDateTime inicio = LocalDateTime.now().minusDays(1);
        ReservaRequest request = new ReservaRequest(cancha.getId(), inicio, inicio.plusHours(1), Deporte.FUTBOL_5);

        assertThrows(IllegalArgumentException.class, () -> reservaService.crearReserva(request, jugador.getEmail()));
    }

    /**
     * ReservaRequest (ver reserva/dto/ReservaRequest.java) ni siquiera tiene un campo de
     * precio: no hay forma de que el cliente mande un monto manipulado en el request, porque
     * el DTO no expone esa propiedad. Este test verifica en runtime que, de todas formas, el
     * precio persistido siempre sale de PrecioReservaCalculator (a partir de la config del
     * lado servidor de la cancha), no de ningún dato del request.
     */
    @Test
    @DisplayName("El precioTotal persistido sale SIEMPRE de la config server-side de la cancha")
    void crearReserva_PrecioSiempreRecalculadoEnServidor() {
        LocalDateTime inicio = FECHA_BASE.atTime(10, 0);
        LocalDateTime fin = FECHA_BASE.atTime(11, 0);
        when(reservaRepository.findSuperpuestas(eq(establecimiento.getId()), eq(inicio), eq(fin), any())).thenReturn(List.of());

        ReservaRequest request = new ReservaRequest(cancha.getId(), inicio, fin, Deporte.FUTBOL_5);
        reservaService.crearReserva(request, jugador.getEmail());

        ArgumentCaptor<Reserva> captor = ArgumentCaptor.forClass(Reserva.class);
        verify(reservaRepository).save(captor.capture());

        // precioBase=1000, duración=60min=1h exacta -> 1000 * 1.00, sin importar nada del request.
        assertEquals(0, BigDecimal.valueOf(1000).compareTo(captor.getValue().getPrecioTotal()));
    }
}
