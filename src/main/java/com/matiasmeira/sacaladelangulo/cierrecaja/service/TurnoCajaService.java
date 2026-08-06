package com.matiasmeira.sacaladelangulo.cierrecaja.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.AbrirCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CajaAbiertaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CerrarCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CierreCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.MovimientoManualRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaDetalleResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaMapper;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.TurnoCajaResumenResponse;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.MovimientoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestión de turnos de caja (apertura, movimientos, cierre/arqueo) de un establecimiento.
 * A propósito no depende de {@code reserva}/{@code buffet}/{@code gastos}: son esos
 * servicios los que van a depender de este a través de
 * {@link #registrarMovimientoSiCorresponde}, y una dependencia en el otro sentido
 * generaría un ciclo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TurnoCajaService {

    private final TurnoCajaRepository turnoCajaRepository;
    private final MovimientoCajaRepository movimientoCajaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final DispositivoCajaRepository dispositivoCajaRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final TurnoCajaMapper turnoCajaMapper;
    private final MovimientoCajaMapper movimientoCajaMapper;

    /**
     * Abre un nuevo turno de caja para el establecimiento. Solo puede haber un turno
     * ABIERTO por establecimiento a la vez.
     */
    @Transactional
    public TurnoCajaResponse abrirCaja(Long establecimientoId, AbrirCajaRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                establecimiento, email, PermisoEmpleado.OPERAR_CAJA);

        try {
            if (request.fondoInicial() == null || request.fondoInicial().signum() < 0) {
                throw new IllegalArgumentException("El fondo inicial no puede ser negativo");
            }

            turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimientoId, EstadoTurnoCaja.ABIERTO)
                    .ifPresent(t -> {
                        throw new IllegalArgumentException("Ya existe un turno de caja abierto para este establecimiento");
                    });

            DispositivoCaja dispositivoCaja = null;
            if (request.dispositivoId() != null) {
                dispositivoCaja = dispositivoCajaRepository.findByIdAndEstablecimientoId(request.dispositivoId(), establecimientoId)
                        .orElseThrow(() -> new EntityNotFoundException("Dispositivo de caja no encontrado"));
            }

            TurnoCaja turno = TurnoCaja.builder()
                    .establecimiento(establecimiento)
                    .dispositivoCaja(dispositivoCaja)
                    .usuarioApertura(usuarioAutenticado)
                    .fondoInicial(request.fondoInicial())
                    .estado(EstadoTurnoCaja.ABIERTO)
                    .build();

            turno = turnoCajaRepository.save(turno);
            log.info("Turno de caja abierto. Establecimiento: {}, Turno: {}, Fondo inicial: {}",
                    establecimientoId, turno.getId(), turno.getFondoInicial());

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.ABRIR_CAJA, turno.getId(), true,
                    "Caja abierta con fondo inicial " + turno.getFondoInicial());
            return turnoCajaMapper.mapToResponse(turno);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.ABRIR_CAJA, null, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Registra un movimiento dentro de un turno ya resuelto. Privado: los llamadores
     * externos siempre pasan por {@link #registrarMovimientoSiCorresponde} o
     * {@link #registrarMovimientoManual}.
     */
    private MovimientoCaja registrarMovimiento(TurnoCaja turno, TipoMovimientoCaja tipo, OrigenMovimientoCaja origen,
                                                MetodoPago metodoPago, BigDecimal monto, String descripcion,
                                                Long referenciaId, Usuario usuario) {
        if (monto == null || monto.signum() <= 0) {
            throw new IllegalArgumentException("El monto debe ser mayor a 0");
        }

        MovimientoCaja movimiento = MovimientoCaja.builder()
                .turnoCaja(turno)
                .tipo(tipo)
                .origen(origen)
                .metodoPago(metodoPago)
                .monto(monto)
                .descripcion(descripcion)
                .referenciaId(referenciaId)
                .usuario(usuario)
                .build();

        movimiento = movimientoCajaRepository.save(movimiento);
        log.info("Movimiento de caja registrado. Turno: {}, Tipo: {}, Origen: {}, Monto: {}",
                turno.getId(), tipo, origen, monto);
        return movimiento;
    }

    /**
     * Punto de entrada único para los hooks de ReservaService/VentaService/GastoService:
     * si el pago no fue en EFECTIVO, o si no hay un turno de caja ABIERTO para el
     * establecimiento, no hace nada (no bloquea ni afecta a la operación de origen,
     * solo deja constancia con un warn).
     */
    @Transactional
    public void registrarMovimientoSiCorresponde(Establecimiento establecimiento, TipoMovimientoCaja tipo,
                                                  OrigenMovimientoCaja origen, MetodoPago metodoPago, BigDecimal monto,
                                                  String descripcion, Long referenciaId, Usuario usuario) {
        if (metodoPago != MetodoPago.EFECTIVO) {
            return;
        }

        Optional<TurnoCaja> turnoAbierto = turnoCajaRepository.findByEstablecimientoIdAndEstado(
                establecimiento.getId(), EstadoTurnoCaja.ABIERTO);
        if (turnoAbierto.isEmpty()) {
            log.warn("No hay turno de caja abierto para el establecimiento {}. No se registra el movimiento de origen {}",
                    establecimiento.getId(), origen);
            return;
        }

        registrarMovimiento(turnoAbierto.get(), tipo, origen, metodoPago, monto, descripcion, referenciaId, usuario);
    }

    /**
     * ¿El último movimiento de caja registrado para esta entidad de origen (venta/gasto)
     * todavía vive en el turno actualmente ABIERTO del establecimiento? Se usa antes de
     * compensar una cancelación/edición (ver VentaService.cancelarVenta,
     * GastoService.editarGasto/eliminarGasto): si el turno donde se registró el movimiento
     * original ya cerró (o nunca se llegó a registrar nada, porque no había turno abierto en
     * su momento), la respuesta es false y el caller NO debe tocar la caja — ni revertir ni
     * registrar un movimiento nuevo — porque cualquiera de las dos cosas ensuciaría el arqueo
     * de un turno que no tuvo ese billete físico (bug real corregido, ver
     * REVISION_FUNCIONAL.md).
     */
    @Transactional(readOnly = true)
    public boolean movimientoOriginalSigueEnTurnoAbierto(Establecimiento establecimiento, OrigenMovimientoCaja origen, Long referenciaId) {
        Optional<TurnoCaja> turnoAbierto = turnoCajaRepository.findByEstablecimientoIdAndEstado(
                establecimiento.getId(), EstadoTurnoCaja.ABIERTO);
        if (turnoAbierto.isEmpty()) {
            return false;
        }
        return movimientoCajaRepository.findTopByOrigenAndReferenciaIdOrderByFechaHoraDesc(origen, referenciaId)
                .map(movimiento -> movimiento.getTurnoCaja().getId().equals(turnoAbierto.get().getId()))
                .orElse(false);
    }

    /**
     * Registra un movimiento manual (siempre EFECTIVO) cargado a mano por el operador
     * dentro del turno ABIERTO del establecimiento.
     */
    @Transactional
    public MovimientoCajaResponse registrarMovimientoManual(Long establecimientoId, MovimientoManualRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                establecimiento, email, PermisoEmpleado.OPERAR_CAJA);

        try {
            TurnoCaja turno = turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimientoId, EstadoTurnoCaja.ABIERTO)
                    .orElseThrow(() -> new EntityNotFoundException("No hay un turno de caja abierto para este establecimiento"));

            MovimientoCaja movimiento = registrarMovimiento(turno, request.tipo(), OrigenMovimientoCaja.MANUAL,
                    MetodoPago.EFECTIVO, request.monto(), request.descripcion(), null, usuarioAutenticado);

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.REGISTRAR_MOVIMIENTO_CAJA, movimiento.getId(), true,
                    "Movimiento manual " + movimiento.getTipo() + " por " + movimiento.getMonto());
            return movimientoCajaMapper.mapToResponse(movimiento);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.REGISTRAR_MOVIMIENTO_CAJA, null, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Cierra un turno de caja: es irreversible, no existe endpoint de reapertura.
     * Calcula el saldo teórico de efectivo (fondo inicial + ingresos en efectivo -
     * egresos en efectivo) y la diferencia contra el saldo real contado por el operador.
     */
    @Transactional
    public CierreCajaResponse cerrarCaja(Long establecimientoId, Long turnoId, CerrarCajaRequest request, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                establecimiento, email, PermisoEmpleado.OPERAR_CAJA);

        try {
            TurnoCaja turno = turnoCajaRepository.findByIdAndEstablecimientoId(turnoId, establecimientoId)
                    .orElseThrow(() -> new EntityNotFoundException("Turno de caja no encontrado"));

            if (turno.getEstado() == EstadoTurnoCaja.CERRADO) {
                throw new IllegalArgumentException("El turno de caja ya se encuentra cerrado");
            }

            if (request.saldoRealContado() == null || request.saldoRealContado().signum() < 0) {
                throw new IllegalArgumentException("El saldo real contado no puede ser negativo");
            }

            List<MovimientoCaja> movimientos = movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId());
            BigDecimal saldoTeoricoEfectivo = calcularSaldoTeoricoEfectivo(turno, movimientos);
            BigDecimal diferencia = request.saldoRealContado().subtract(saldoTeoricoEfectivo);

            turno.setEstado(EstadoTurnoCaja.CERRADO);
            turno.setFechaCierre(LocalDateTime.now());
            turno.setUsuarioCierre(usuarioAutenticado);
            turno.setSaldoTeoricoEfectivo(saldoTeoricoEfectivo);
            turno.setSaldoRealContado(request.saldoRealContado());
            turno.setDiferencia(diferencia);
            turno.setObservaciones(request.observaciones());

            turno = turnoCajaRepository.save(turno);
            log.info("Turno de caja cerrado. Turno: {}, Teórico: {}, Real: {}, Diferencia: {}",
                    turno.getId(), saldoTeoricoEfectivo, request.saldoRealContado(), diferencia);

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CERRAR_CAJA, turno.getId(), true,
                    "Caja cerrada. Diferencia: " + diferencia);
            return turnoCajaMapper.mapToCierre(turno);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.CERRAR_CAJA, turnoId, false, ex.getMessage());
            throw ex;
        }
    }

    /**
     * saldoTeoricoEfectivo = fondoInicial + Σ(INGRESO en efectivo) - Σ(EGRESO en
     * efectivo). Solo se computan los movimientos en EFECTIVO: los demás métodos de
     * pago no forman parte del efectivo físico de la caja.
     */
    private BigDecimal calcularSaldoTeoricoEfectivo(TurnoCaja turno, List<MovimientoCaja> movimientos) {
        BigDecimal ingresosEfectivo = movimientos.stream()
                .filter(m -> m.getTipo() == TipoMovimientoCaja.INGRESO && m.getMetodoPago() == MetodoPago.EFECTIVO)
                .map(MovimientoCaja::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal egresosEfectivo = movimientos.stream()
                .filter(m -> m.getTipo() == TipoMovimientoCaja.EGRESO && m.getMetodoPago() == MetodoPago.EFECTIVO)
                .map(MovimientoCaja::getMonto)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return turno.getFondoInicial().add(ingresosEfectivo).subtract(egresosEfectivo);
    }

    /**
     * Estado en vivo del turno ABIERTO del establecimiento: saldo teórico calculado
     * hasta el momento y totales de ingresos/egresos por método de pago.
     */
    @Transactional(readOnly = true)
    public CajaAbiertaResponse getCajaAbierta(Long establecimientoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarAccion(establecimiento, email, PermisoEmpleado.OPERAR_CAJA);

        TurnoCaja turno = turnoCajaRepository.findByEstablecimientoIdAndEstado(establecimientoId, EstadoTurnoCaja.ABIERTO)
                .orElseThrow(() -> new EntityNotFoundException("No hay un turno de caja abierto para este establecimiento"));

        List<MovimientoCaja> movimientos = movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId());
        BigDecimal saldoTeoricoEfectivo = calcularSaldoTeoricoEfectivo(turno, movimientos);

        Map<MetodoPago, BigDecimal> totalIngresosPorMetodoPago = new EnumMap<>(MetodoPago.class);
        Map<MetodoPago, BigDecimal> totalEgresosPorMetodoPago = new EnumMap<>(MetodoPago.class);
        for (MovimientoCaja movimiento : movimientos) {
            Map<MetodoPago, BigDecimal> destino = movimiento.getTipo() == TipoMovimientoCaja.INGRESO
                    ? totalIngresosPorMetodoPago : totalEgresosPorMetodoPago;
            destino.merge(movimiento.getMetodoPago(), movimiento.getMonto(), BigDecimal::add);
        }

        return new CajaAbiertaResponse(
                turnoCajaMapper.mapToResponse(turno),
                saldoTeoricoEfectivo,
                totalIngresosPorMetodoPago,
                totalEgresosPorMetodoPago
        );
    }

    /**
     * Lista el historial de turnos de caja del establecimiento. Restringido a
     * OWNER/ADMIN: a diferencia de las demás operaciones de este servicio, no queda
     * accesible a un empleado con OPERAR_CAJA.
     */
    @Transactional(readOnly = true)
    public Page<TurnoCajaResumenResponse> listarTurnos(Long establecimientoId, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return turnoCajaRepository.findByEstablecimientoIdOrderByFechaAperturaDesc(establecimientoId, pageable)
                .map(turnoCajaMapper::mapToResumen);
    }

    /**
     * Detalle de un turno de caja puntual (con todos sus movimientos). Restringido a
     * OWNER/ADMIN, igual que {@link #listarTurnos}.
     */
    @Transactional(readOnly = true)
    public TurnoCajaDetalleResponse getDetalleTurno(Long establecimientoId, Long turnoId, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        TurnoCaja turno = turnoCajaRepository.findByIdAndEstablecimientoId(turnoId, establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Turno de caja no encontrado"));

        List<MovimientoCaja> movimientos = movimientoCajaRepository.findByTurnoCajaIdOrderByFechaHoraAsc(turno.getId());
        return turnoCajaMapper.mapToDetalle(turno, movimientos);
    }

    private void registrarAuditoriaSiEsEmpleado(Usuario usuario, AccionAuditoria accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
        if (usuario.getRol() == Role.EMPLOYEE) {
            registroAuditoriaService.registrar(usuario, accion, entidadAfectadaId, exitoso, detalle);
        }
    }
}
