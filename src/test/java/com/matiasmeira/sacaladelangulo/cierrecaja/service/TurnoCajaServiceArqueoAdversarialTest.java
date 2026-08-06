package com.matiasmeira.sacaladelangulo.cierrecaja.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CerrarCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CierreCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.MovimientoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Casos de arqueo de caja que TurnoCajaServiceTest no cubre todavía: diferencia EXACTA
 * (cero) y un turno con movimientos SOLO no-efectivo (el saldo teórico en efectivo debe
 * quedar igual al fondo inicial, sin que la transferencia/tarjeta "cuente" para nada).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TurnoCajaService - Arqueo: diferencia exacta y solo movimientos no-efectivo")
class TurnoCajaServiceArqueoAdversarialTest {

    @Mock private TurnoCajaRepository turnoCajaRepository;
    @Mock private MovimientoCajaRepository movimientoCajaRepository;
    @Mock private EstablecimientoRepository establecimientoRepository;
    @Mock private DispositivoCajaRepository dispositivoCajaRepository;
    @Mock private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock private RegistroAuditoriaService registroAuditoriaService;

    private TurnoCajaService turnoCajaService;
    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        turnoCajaService = new TurnoCajaService(
                turnoCajaRepository, movimientoCajaRepository, establecimientoRepository, dispositivoCajaRepository,
                autorizacionEmpleadoService, registroAuditoriaService,
                new TurnoCajaMapper(new MovimientoCajaMapper()), new MovimientoCajaMapper());

        dueno = Usuario.builder().id(2L).email("dueno@test.com").nombre("Dueño Test").rol(Role.OWNER).build();
        establecimiento = Establecimiento.builder().id(10L).nombre("Establecimiento Test").dueno(dueno).build();
    }

    private TurnoCaja turnoAbierto(BigDecimal fondoInicial) {
        return TurnoCaja.builder().id(100L).establecimiento(establecimiento).usuarioApertura(dueno)
                .fechaApertura(LocalDateTime.now().minusHours(2)).fondoInicial(fondoInicial)
                .estado(EstadoTurnoCaja.ABIERTO).build();
    }

    private MovimientoCaja movimiento(TurnoCaja turno, TipoMovimientoCaja tipo, MetodoPago metodoPago, BigDecimal monto) {
        return MovimientoCaja.builder().id(200L).turnoCaja(turno).tipo(tipo).origen(OrigenMovimientoCaja.RESERVA)
                .metodoPago(metodoPago).monto(monto).fechaHora(LocalDateTime.now()).usuario(dueno).build();
    }

    private void stubComunes(TurnoCaja turno, List<MovimientoCaja> movimientos) {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId())).thenReturn(movimientos);
        when(turnoCajaRepository.save(any(TurnoCaja.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("cerrarCaja: diferencia EXACTA (real == teórico) resulta en EXACTO")
    void cerrarCaja_DiferenciaExactaCero_Resultado_Exacto() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(1000));
        List<MovimientoCaja> movimientos = List.of(
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(500)),
                movimiento(turno, TipoMovimientoCaja.EGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(200))
        );
        // teorico = 1000 + 500 - 200 = 1300
        stubComunes(turno, movimientos);
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(1300), "cuadra justo");

        CierreCajaResponse response = turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail());

        assertEquals(0, BigDecimal.ZERO.compareTo(response.diferencia()));
        assertEquals("EXACTO", response.resultado());
    }

    @Test
    @DisplayName("cerrarCaja: turno con movimientos SOLO no-efectivo deja el saldo teórico igual al fondo inicial")
    void cerrarCaja_SoloMovimientosNoEfectivo_SaldoTeoricoIgualAlFondoInicial() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(2000));
        List<MovimientoCaja> movimientos = List.of(
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.TRANSFERENCIA, BigDecimal.valueOf(50000)),
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.MERCADO_PAGO, BigDecimal.valueOf(30000)),
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.TARJETA_CREDITO, BigDecimal.valueOf(10000)),
                movimiento(turno, TipoMovimientoCaja.EGRESO, MetodoPago.TRANSFERENCIA, BigDecimal.valueOf(5000))
        );
        stubComunes(turno, movimientos);
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(2000), "nadie tocó efectivo");

        CierreCajaResponse response = turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(2000).compareTo(response.saldoTeoricoEfectivo()));
        assertEquals(0, BigDecimal.ZERO.compareTo(response.diferencia()));
        assertEquals("EXACTO", response.resultado());
    }
}
