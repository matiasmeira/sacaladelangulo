package com.matiasmeira.sacaladelangulo.cierrecaja.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.AbrirCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CajaAbiertaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CerrarCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CierreCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoManualRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.MovimientoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
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
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TurnoCajaService - Tests del gestor de turnos de caja")
class TurnoCajaServiceTest {

    @Mock
    private TurnoCajaRepository turnoCajaRepository;

    @Mock
    private MovimientoCajaRepository movimientoCajaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    private TurnoCajaService turnoCajaService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        turnoCajaService = new TurnoCajaService(
                turnoCajaRepository,
                movimientoCajaRepository,
                establecimientoRepository,
                dispositivoCajaRepository,
                autorizacionEmpleadoService,
                registroAuditoriaService,
                new TurnoCajaMapper(new MovimientoCajaMapper()),
                new MovimientoCajaMapper()
        );

        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .dueno(dueno)
                .build();
    }

    private TurnoCaja turnoAbierto(BigDecimal fondoInicial) {
        return TurnoCaja.builder()
                .id(100L)
                .establecimiento(establecimiento)
                .usuarioApertura(dueno)
                .fechaApertura(LocalDateTime.now().minusHours(2))
                .fondoInicial(fondoInicial)
                .estado(EstadoTurnoCaja.ABIERTO)
                .build();
    }

    private MovimientoCaja movimiento(TurnoCaja turno, TipoMovimientoCaja tipo, MetodoPago metodoPago, BigDecimal monto) {
        return MovimientoCaja.builder()
                .id(200L)
                .turnoCaja(turno)
                .tipo(tipo)
                .origen(OrigenMovimientoCaja.RESERVA)
                .metodoPago(metodoPago)
                .monto(monto)
                .fechaHora(LocalDateTime.now())
                .usuario(dueno)
                .build();
    }

    // ---------- abrirCaja ----------

    @Test
    @DisplayName("abrirCaja_Exito_CreaTurnoAbierto")
    void abrirCaja_Exito_CreaTurnoAbierto() {
        AbrirCajaRequest request = new AbrirCajaRequest(BigDecimal.valueOf(5000), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.empty());
        when(turnoCajaRepository.save(any(TurnoCaja.class))).thenAnswer(invocation -> {
            TurnoCaja turno = invocation.getArgument(0);
            turno.setId(100L);
            turno.setFechaApertura(LocalDateTime.now());
            return turno;
        });

        TurnoCajaResponse response = assertDoesNotThrow(
                () -> turnoCajaService.abrirCaja(establecimiento.getId(), request, dueno.getEmail()));

        assertEquals(0, BigDecimal.valueOf(5000).compareTo(response.fondoInicial()));
        assertEquals("ABIERTO", response.estado());
    }

    @Test
    @DisplayName("abrirCaja_Fallo_YaExisteTurnoAbierto")
    void abrirCaja_Fallo_YaExisteTurnoAbierto() {
        AbrirCajaRequest request = new AbrirCajaRequest(BigDecimal.valueOf(5000), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.of(turnoAbierto(BigDecimal.ZERO)));

        assertThrows(IllegalArgumentException.class,
                () -> turnoCajaService.abrirCaja(establecimiento.getId(), request, dueno.getEmail()));
        verify(turnoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("abrirCaja_Fallo_FondoInicialNegativo")
    void abrirCaja_Fallo_FondoInicialNegativo() {
        AbrirCajaRequest request = new AbrirCajaRequest(BigDecimal.valueOf(-100), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);

        assertThrows(IllegalArgumentException.class,
                () -> turnoCajaService.abrirCaja(establecimiento.getId(), request, dueno.getEmail()));
        verify(turnoCajaRepository, never()).save(any());
    }

    // ---------- registrarMovimientoSiCorresponde ----------

    @Test
    @DisplayName("registrarMovimientoSiCorresponde_NoOp_MetodoPagoNoEfectivo")
    void registrarMovimientoSiCorresponde_NoOp_MetodoPagoNoEfectivo() {
        assertDoesNotThrow(() -> turnoCajaService.registrarMovimientoSiCorresponde(
                establecimiento, TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.RESERVA,
                MetodoPago.TRANSFERENCIA, BigDecimal.valueOf(1000), "Reserva", 1L, dueno));

        verify(turnoCajaRepository, never()).findByEstablecimientoIdAndEstado(any(), any());
        verify(movimientoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarMovimientoSiCorresponde_NoOp_SinTurnoAbierto")
    void registrarMovimientoSiCorresponde_NoOp_SinTurnoAbierto() {
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.empty());

        assertDoesNotThrow(() -> turnoCajaService.registrarMovimientoSiCorresponde(
                establecimiento, TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.RESERVA,
                MetodoPago.EFECTIVO, BigDecimal.valueOf(1000), "Reserva", 1L, dueno));

        verify(movimientoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarMovimientoSiCorresponde_Exito_RegistraMovimientoEnTurnoAbierto")
    void registrarMovimientoSiCorresponde_Exito_RegistraMovimientoEnTurnoAbierto() {
        TurnoCaja turno = turnoAbierto(BigDecimal.ZERO);

        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.save(any(MovimientoCaja.class))).thenAnswer(invocation -> invocation.getArgument(0));

        turnoCajaService.registrarMovimientoSiCorresponde(
                establecimiento, TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.RESERVA,
                MetodoPago.EFECTIVO, BigDecimal.valueOf(1500), "Reserva finalizada", 5L, dueno);

        verify(movimientoCajaRepository).save(any(MovimientoCaja.class));
    }

    // ---------- registrarMovimientoManual ----------

    @Test
    @DisplayName("registrarMovimientoManual_Fallo_SinTurnoAbierto")
    void registrarMovimientoManual_Fallo_SinTurnoAbierto() {
        MovimientoManualRequest request = new MovimientoManualRequest(TipoMovimientoCaja.EGRESO, BigDecimal.valueOf(500), "Compra insumos");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> turnoCajaService.registrarMovimientoManual(establecimiento.getId(), request, dueno.getEmail()));
        verify(movimientoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarMovimientoManual_Exito_SiempreEfectivo")
    void registrarMovimientoManual_Exito_SiempreEfectivo() {
        TurnoCaja turno = turnoAbierto(BigDecimal.ZERO);
        MovimientoManualRequest request = new MovimientoManualRequest(TipoMovimientoCaja.EGRESO, BigDecimal.valueOf(500), "Compra insumos");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.save(any(MovimientoCaja.class))).thenAnswer(invocation -> {
            MovimientoCaja movimiento = invocation.getArgument(0);
            movimiento.setId(300L);
            movimiento.setFechaHora(LocalDateTime.now());
            return movimiento;
        });

        MovimientoCajaResponse response = turnoCajaService.registrarMovimientoManual(establecimiento.getId(), request, dueno.getEmail());

        assertEquals("EFECTIVO", response.metodoPago());
        assertEquals("EGRESO", response.tipo());
        assertEquals(0, BigDecimal.valueOf(500).compareTo(response.monto()));
    }

    // ---------- cerrarCaja ----------

    @Test
    @DisplayName("cerrarCaja_Exito_CalculaSobranteCuandoRealEsMayorAlTeorico")
    void cerrarCaja_Exito_CalculaSobranteCuandoRealEsMayorAlTeorico() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(1000));
        List<MovimientoCaja> movimientos = List.of(
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(2000)),
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.TRANSFERENCIA, BigDecimal.valueOf(9000)),
                movimiento(turno, TipoMovimientoCaja.EGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(300))
        );
        // teorico = 1000 + 2000 - 300 = 2700
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(2750), "Todo ok");

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId())).thenReturn(movimientos);
        when(turnoCajaRepository.save(any(TurnoCaja.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CierreCajaResponse response = turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(2700).compareTo(response.saldoTeoricoEfectivo()));
        assertEquals(0, BigDecimal.valueOf(50).compareTo(response.diferencia()));
        assertEquals("SOBRANTE", response.resultado());
    }

    @Test
    @DisplayName("cerrarCaja_Exito_CalculaFaltanteCuandoRealEsMenorAlTeorico")
    void cerrarCaja_Exito_CalculaFaltanteCuandoRealEsMenorAlTeorico() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(1000));
        List<MovimientoCaja> movimientos = List.of(
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(2000))
        );
        // teorico = 1000 + 2000 = 3000
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(2900), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId())).thenReturn(movimientos);
        when(turnoCajaRepository.save(any(TurnoCaja.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CierreCajaResponse response = turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(3000).compareTo(response.saldoTeoricoEfectivo()));
        assertEquals(0, BigDecimal.valueOf(-100).compareTo(response.diferencia()));
        assertEquals("FALTANTE", response.resultado());
    }

    @Test
    @DisplayName("cerrarCaja_Fallo_TurnoYaCerrado")
    void cerrarCaja_Fallo_TurnoYaCerrado() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(1000));
        turno.setEstado(EstadoTurnoCaja.CERRADO);
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(1000), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));

        assertThrows(IllegalArgumentException.class,
                () -> turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail()));
        verify(turnoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("cerrarCaja_Fallo_SaldoRealNegativo")
    void cerrarCaja_Fallo_SaldoRealNegativo() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(1000));
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(-1), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));

        assertThrows(IllegalArgumentException.class,
                () -> turnoCajaService.cerrarCaja(establecimiento.getId(), turno.getId(), request, dueno.getEmail()));
        verify(turnoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("cerrarCaja_Fallo_TurnoNoEncontrado")
    void cerrarCaja_Fallo_TurnoNoEncontrado() {
        CerrarCajaRequest request = new CerrarCajaRequest(BigDecimal.valueOf(1000), null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(999L, establecimiento.getId()))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> turnoCajaService.cerrarCaja(establecimiento.getId(), 999L, request, dueno.getEmail()));
    }

    // ---------- getCajaAbierta ----------

    @Test
    @DisplayName("getCajaAbierta_Fallo_SinTurnoAbierto")
    void getCajaAbierta_Fallo_SinTurnoAbierto() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> turnoCajaService.getCajaAbierta(establecimiento.getId(), dueno.getEmail()));
    }

    @Test
    @DisplayName("getCajaAbierta_Exito_CalculaSaldoTeoricoEnVivo")
    void getCajaAbierta_Exito_CalculaSaldoTeoricoEnVivo() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(500));
        List<MovimientoCaja> movimientos = List.of(
                movimiento(turno, TipoMovimientoCaja.INGRESO, MetodoPago.EFECTIVO, BigDecimal.valueOf(1000))
        );

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, dueno.getEmail(), PermisoEmpleado.OPERAR_CAJA))
                .thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimiento.getId(), EstadoTurnoCaja.ABIERTO))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId())).thenReturn(movimientos);

        CajaAbiertaResponse response = turnoCajaService.getCajaAbierta(establecimiento.getId(), dueno.getEmail());

        assertEquals(0, BigDecimal.valueOf(1500).compareTo(response.saldoTeoricoEfectivo()));
    }

    // ---------- listarTurnos / getDetalleTurno: solo OWNER/ADMIN ----------

    @Test
    @DisplayName("listarTurnos_UsaValidarPropietarioOAdmin_NoValidarAccion")
    void listarTurnos_UsaValidarPropietarioOAdmin_NoValidarAccion() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(turnoCajaRepository.findByEstablecimientoIdOrderByFechaAperturaDesc(eq(establecimiento.getId()), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(turnoAbierto(BigDecimal.ZERO))));

        turnoCajaService.listarTurnos(establecimiento.getId(), org.springframework.data.domain.PageRequest.of(0, 10), dueno.getEmail());

        verify(autorizacionEmpleadoService).validarPropietarioOAdmin(establecimiento, dueno.getEmail());
        verify(autorizacionEmpleadoService, never()).validarAccion(any(), any(), any());
    }

    @Test
    @DisplayName("listarTurnos_Fallo_EmpleadoConOperarCajaNoAutorizado")
    void listarTurnos_Fallo_EmpleadoConOperarCajaNoAutorizado() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, "empleado@test.com"))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class, () -> turnoCajaService.listarTurnos(
                establecimiento.getId(), org.springframework.data.domain.PageRequest.of(0, 10), "empleado@test.com"));
    }

    @Test
    @DisplayName("getDetalleTurno_Exito_UsaValidarPropietarioOAdmin")
    void getDetalleTurno_Exito_UsaValidarPropietarioOAdmin() {
        TurnoCaja turno = turnoAbierto(BigDecimal.valueOf(500));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(turnoCajaRepository.findByIdAndEstablecimientoId(turno.getId(), establecimiento.getId()))
                .thenReturn(Optional.of(turno));
        when(movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId())).thenReturn(List.of());

        var detalle = turnoCajaService.getDetalleTurno(establecimiento.getId(), turno.getId(), dueno.getEmail());

        assertEquals(turno.getId(), detalle.turno().id());
        verify(autorizacionEmpleadoService).validarPropietarioOAdmin(establecimiento, dueno.getEmail());
    }
}
