package com.matiasmeira.sacaladelangulo.reserva.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.ReservaExpiradaException;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoJugadorRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaMapper;
import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaResponse;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Matriz COMPLETA de transiciones de estado de Reserva: para cada acción (confirmar,
 * cancelar, finalizar, marcarAusente, revertirAusencia) y cada uno de los 6 estados
 * posibles, se verifica que las transiciones válidas pasen y las inválidas se rechacen.
 *
 * <p>Un caso de esta matriz queda documentado como bug real (ver
 * {@link #finalizar_matriz(EstadoReserva)} para CANCELADA_PRERESERVA): el test se deja en
 * rojo a propósito en vez de ajustar la expectativa para que pase (ver REVISION_FUNCIONAL.md).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservaService - Matriz completa de transiciones de estado")
class ReservaServiceMatrizTransicionesTest {

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

    @org.mockito.InjectMocks
    private ReservaService reservaService;

    private Usuario dueno;
    private Usuario empleado;
    private Establecimiento establecimiento;
    private Cancha cancha;

    private static final Long RESERVA_ID = 1L;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder().id(2L).email("dueno@test.com").password("x").nombre("Dueño")
                .rol(Role.OWNER).planSuscripcion(PlanSuscripcion.PREMIUM).isActive(true)
                .emailVerified(true).telefonoVerificado(false).build();

        empleado = Usuario.builder().id(3L).email("empleado@test.com").password("x").nombre("Empleado")
                .rol(Role.EMPLOYEE).isActive(true).emailVerified(true).telefonoVerificado(false)
                .permisos(Set.of()).build();

        establecimiento = Establecimiento.builder().id(10L).nombre("Club").direccion("Calle 1")
                .latitud(0.0).longitud(0.0).dueno(dueno).requiereSena(false).isActive(true).build();

        cancha = Cancha.builder().id(100L).nombre("Cancha 1").establecimiento(establecimiento)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.ZERO).isActive(true).build();

        lenient().when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        lenient().when(usuarioRepository.findByEmail(empleado.getEmail())).thenReturn(Optional.of(empleado));
        lenient().when(autorizacionEmpleadoService.validarAccion(any(), any(), any())).thenReturn(dueno);
        // validarPropietarioOAdmin (usado por revertirAusencia, entre otros): el dueño real
        // pasa, un EMPLOYEE nunca pasa este chequeo puntual (a diferencia de validarAccion).
        lenient().when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        lenient().when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, empleado.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));
        lenient().when(reservaRepository.save(any(Reserva.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reservaMapper.mapToResponse(any(Reserva.class))).thenAnswer(inv -> {
            Reserva r = inv.getArgument(0);
            return new ReservaResponse(r.getId(), null, null, r.getCancha().getId(), r.getCancha().getNombre(),
                    r.getFechaHoraInicio(), r.getFechaHoraFin(), r.getEstado().name(), r.getPrecioTotal(),
                    r.getSenaPagada(), null, null, r.getDeporteSeleccionado(), r.getExpiraEn(),
                    r.getMetodoPago() != null ? r.getMetodoPago().name() : null,
                    r.getTurnoFijo() != null ? r.getTurnoFijo().getId() : null);
        });
    }

    /** Reserva "genérica" en el estado dado, con el turno ya empezado (para no chocar con la
     * validación de "todavía no empezó" en marcarAusente) y sin ventana de expiración vencida. */
    private Reserva reservaEn(EstadoReserva estado) {
        return Reserva.builder()
                .id(RESERVA_ID)
                .cancha(cancha)
                .jugador(null)
                .fechaHoraInicio(LocalDateTime.now().minusHours(2))
                .fechaHoraFin(LocalDateTime.now().minusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .senaPagada(BigDecimal.ZERO)
                .expiraEn(null)
                .build();
    }

    private static final Set<EstadoReserva> CONFIRMAR_VALIDOS = EnumSet.of(EstadoReserva.PENDIENTE_SENA, EstadoReserva.CONFIRMADA);

    @ParameterizedTest(name = "confirmar desde {0}")
    @EnumSource(EstadoReserva.class)
    @DisplayName("confirmarReserva: matriz de estados")
    void confirmar_matriz(EstadoReserva estadoInicial) {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reservaEn(estadoInicial)));

        if (CONFIRMAR_VALIDOS.contains(estadoInicial)) {
            assertDoesNotThrow(() -> reservaService.confirmarReserva(RESERVA_ID, dueno.getEmail()));
        } else {
            assertThrows(IllegalArgumentException.class, () -> reservaService.confirmarReserva(RESERVA_ID, dueno.getEmail()));
        }
    }

    /**
     * AUSENTE se sumó acá al cerrarse el hueco que este mismo test documentaba como
     * tolerado: cancelar un no-show lo convertía en CANCELADA y borraba el registro de
     * que el jugador no se presentó (y, como el jugador es actor autorizado de
     * cancelarReserva, era él quien podía borrarlo). La salida de AUSENTE es
     * revertirAusencia, restringida a dueño/admin.
     */
    private static final Set<EstadoReserva> CANCELAR_INVALIDOS =
            EnumSet.of(EstadoReserva.FINALIZADA, EstadoReserva.AUSENTE);

    @ParameterizedTest(name = "cancelar desde {0}")
    @EnumSource(EstadoReserva.class)
    @DisplayName("cancelarReserva: matriz de estados (dueño, sin plazo de cancelación de por medio)")
    void cancelar_matriz(EstadoReserva estadoInicial) {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reservaEn(estadoInicial)));

        if (CANCELAR_INVALIDOS.contains(estadoInicial)) {
            assertThrows(IllegalArgumentException.class, () -> reservaService.cancelarReserva(RESERVA_ID, dueno.getEmail()));
        } else {
            // CANCELADA y CANCELADA_PRERESERVA no lanzan, pero tampoco reescriben: las dos
            // salen por el retorno idempotente, cada una conservando su propio estado (ver
            // cancelarReserva_YaCanceladaPrereserva_ConservaElEstadoDeVencimiento). Que no
            // tiren es lo que esta matriz verifica; que no se pisen, el test puntual.
            assertDoesNotThrow(() -> reservaService.cancelarReserva(RESERVA_ID, dueno.getEmail()));
        }
    }

    /**
     * AUSENTE se sumó al cerrarse la mitad que le faltaba al fix de CANCELADA_PRERESERVA:
     * finalizar un no-show cobraba el saldo y movía la caja por un turno que nadie jugó.
     */
    private static final Set<EstadoReserva> FINALIZAR_INVALIDOS = EnumSet.of(
            EstadoReserva.PENDIENTE_SENA, EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA,
            EstadoReserva.AUSENTE);

    @ParameterizedTest(name = "finalizar desde {0}")
    @EnumSource(EstadoReserva.class)
    @DisplayName("finalizarReserva: matriz de estados")
    void finalizar_matriz(EstadoReserva estadoInicial) {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reservaEn(estadoInicial)));

        if (FINALIZAR_INVALIDOS.contains(estadoInicial)) {
            // FIX aplicado (ver REVISION_FUNCIONAL.md): CANCELADA_PRERESERVA (una prereserva
            // que expiró SIN que nadie pagara la seña) ahora se rechaza explícitamente, igual
            // que CANCELADA. Antes caía en la rama por defecto y se podía "finalizar" igual,
            // generando cobro y movimiento de caja sobre una reserva que nunca fue confirmada
            // — este test lo tuvo en rojo hasta que se corrigió ReservaService.finalizarReserva.
            assertThrows(IllegalArgumentException.class,
                    () -> reservaService.finalizarReserva(RESERVA_ID, MetodoPago.EFECTIVO, dueno.getEmail()));
        } else {
            assertDoesNotThrow(() -> reservaService.finalizarReserva(RESERVA_ID, MetodoPago.EFECTIVO, dueno.getEmail()));
        }
    }

    private static final Set<EstadoReserva> MARCAR_AUSENTE_VALIDOS = EnumSet.of(EstadoReserva.CONFIRMADA, EstadoReserva.AUSENTE);

    @ParameterizedTest(name = "marcarAusente desde {0}")
    @EnumSource(EstadoReserva.class)
    @DisplayName("marcarAusente: matriz de estados (turno ya empezado)")
    void marcarAusente_matriz(EstadoReserva estadoInicial) {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reservaEn(estadoInicial)));

        if (MARCAR_AUSENTE_VALIDOS.contains(estadoInicial)) {
            assertDoesNotThrow(() -> reservaService.marcarAusente(RESERVA_ID, dueno.getEmail()));
        } else {
            assertThrows(IllegalArgumentException.class, () -> reservaService.marcarAusente(RESERVA_ID, dueno.getEmail()));
        }
    }

    @ParameterizedTest(name = "revertirAusencia desde {0}")
    @EnumSource(EstadoReserva.class)
    @DisplayName("revertirAusencia: matriz de estados (dueño)")
    void revertirAusencia_matriz(EstadoReserva estadoInicial) {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reservaEn(estadoInicial)));

        if (estadoInicial == EstadoReserva.AUSENTE) {
            assertDoesNotThrow(() -> reservaService.revertirAusencia(RESERVA_ID, dueno.getEmail()));
        } else {
            assertThrows(IllegalArgumentException.class, () -> reservaService.revertirAusencia(RESERVA_ID, dueno.getEmail()));
        }
    }

    @Test
    @DisplayName("revertirAusencia: un EMPLOYEE nunca puede, aunque tenga MARCAR_AUSENTE (es owner/admin-only)")
    void revertirAusencia_Empleado_Falla() {
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID))
                .thenReturn(Optional.of(reservaEn(EstadoReserva.AUSENTE)));

        assertThrows(AccessDeniedException.class, () -> reservaService.revertirAusencia(RESERVA_ID, empleado.getEmail()));
    }

    @Test
    @DisplayName("marcarAusente: turno futuro falla")
    void marcarAusente_TurnoFuturo_Falla() {
        Reserva reserva = reservaEn(EstadoReserva.CONFIRMADA);
        reserva.setFechaHoraInicio(LocalDateTime.now().plusHours(1));
        reserva.setFechaHoraFin(LocalDateTime.now().plusHours(2));
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reserva));

        assertThrows(IllegalArgumentException.class, () -> reservaService.marcarAusente(RESERVA_ID, dueno.getEmail()));
    }

    @Test
    @DisplayName("marcarAusente: borde exacto en la hora de inicio (ya se puede marcar)")
    void marcarAusente_BordeExactoHoraInicio_Permitido() {
        // fechaHoraInicio se fija ANTES de invocar al service: como el reloj solo avanza,
        // el LocalDateTime.now() que evalúa el service nunca puede ser "antes" que este
        // valor ya capturado -> el borde exacto (ni un nanosegundo de margen) debe pasar.
        LocalDateTime inicioExacto = LocalDateTime.now();
        Reserva reserva = reservaEn(EstadoReserva.CONFIRMADA);
        reserva.setFechaHoraInicio(inicioExacto);
        reserva.setFechaHoraFin(inicioExacto.plusHours(1));
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reserva));

        assertDoesNotThrow(() -> reservaService.marcarAusente(RESERVA_ID, dueno.getEmail()));
    }

    @Test
    @DisplayName("confirmarReserva: un segundo antes de expirar, todavía confirma")
    void confirmarReserva_UnSegundoAntesDeExpirar_Confirma() {
        Reserva reserva = reservaEn(EstadoReserva.PENDIENTE_SENA);
        reserva.setExpiraEn(LocalDateTime.now().plusSeconds(1));
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reserva));

        assertDoesNotThrow(() -> reservaService.confirmarReserva(RESERVA_ID, dueno.getEmail()));
    }

    @Test
    @DisplayName("confirmarReserva: un segundo después de expirar, rechaza con ReservaExpiradaException")
    void confirmarReserva_UnSegundoDespuesDeExpirar_Rechaza() {
        Reserva reserva = reservaEn(EstadoReserva.PENDIENTE_SENA);
        reserva.setExpiraEn(LocalDateTime.now().minusSeconds(1));
        when(reservaRepository.findByIdConEstablecimientoYDueno(RESERVA_ID)).thenReturn(Optional.of(reserva));

        assertThrows(ReservaExpiradaException.class, () -> reservaService.confirmarReserva(RESERVA_ID, dueno.getEmail()));
    }
}
