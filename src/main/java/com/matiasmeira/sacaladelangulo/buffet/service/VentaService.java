package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.dto.DetalleVentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.OrigenMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TipoMovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.service.TurnoCajaService;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Servicio de negocio para el registro de ventas del buffet.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VentaService {

    private final VentaRepository ventaRepository;
    private final ProductoBuffetRepository productoBuffetRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final VentaMapper ventaMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final TurnoCajaService turnoCajaService;

    /**
     * Registra una venta de uno o más productos del buffet, descontando el stock
     * correspondiente y calculando el total a partir del precio actual de cada
     * producto (nunca del precio que mande el frontend). No se rechaza la venta si
     * el stock no alcanza: el stock puede quedar en negativo (por ejemplo, si el
     * dueño todavía no cargó el stock inicial de un producto nuevo) — solo se
     * registra un warning para que quede visible que hay que reponer/corregir.
     */
    @Transactional
    public VentaResponse registrarVenta(VentaRequest request, String email) {
        log.info("Iniciando registro de venta. Email: {}, Establecimiento: {}, Items: {}",
                email, request.establecimientoId(), request.detalles().size());

        Establecimiento establecimiento = establecimientoRepository.findById(request.establecimientoId())
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarAccion(
                establecimiento, email, PermisoEmpleado.REGISTRAR_VENTA_BUFFET);

        try {
            Reserva reserva = resolverReserva(request.reservaId(), establecimiento.getId());

            Venta venta = Venta.builder()
                    .establecimiento(establecimiento)
                    .reserva(reserva)
                    .fechaHora(LocalDateTime.now())
                    .total(BigDecimal.ZERO)
                    .estado(EstadoVenta.CONFIRMADA)
                    .metodoPago(request.metodoPago())
                    .build();

            // Lock pesimista sobre todos los productos del carrito antes de leer/escribir
            // stock, en orden ascendente de ID para evitar deadlocks entre ventas
            // concurrentes que comparten productos en distinto orden (ver A5).
            List<Long> productoIdsOrdenados = request.detalles().stream()
                    .map(DetalleVentaRequest::productoId)
                    .distinct()
                    .sorted()
                    .toList();
            productoBuffetRepository.lockPorIds(productoIdsOrdenados);

            List<DetalleVenta> detalles = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;

            for (DetalleVentaRequest detalleRequest : request.detalles()) {
                ProductoBuffet producto = buscarProductoDelEstablecimiento(establecimiento.getId(), detalleRequest.productoId());

                BigDecimal subtotal = producto.getPrecio().multiply(BigDecimal.valueOf(detalleRequest.cantidad()));
                total = total.add(subtotal);

                int nuevoStock = producto.getStock() - detalleRequest.cantidad();
                if (nuevoStock < 0) {
                    log.warn("La venta deja stock negativo. Producto: {}, Stock previo: {}, Vendido: {}, Nuevo stock: {}",
                            producto.getId(), producto.getStock(), detalleRequest.cantidad(), nuevoStock);
                }
                producto.setStock(nuevoStock);
                productoBuffetRepository.save(producto);

                detalles.add(DetalleVenta.builder()
                        .venta(venta)
                        .productoBuffet(producto)
                        .cantidad(detalleRequest.cantidad())
                        .subtotal(subtotal)
                        .build());
            }

            venta.setTotal(total);
            venta.setDetalles(detalles);

            Venta ventaGuardada = ventaRepository.save(venta);
            log.info("Venta registrada con éxito. ID: {}, Establecimiento: {}, Total: {}",
                    ventaGuardada.getId(), establecimiento.getId(), total);

            turnoCajaService.registrarMovimientoSiCorresponde(
                    establecimiento, TipoMovimientoCaja.INGRESO, OrigenMovimientoCaja.VENTA_BUFFET,
                    ventaGuardada.getMetodoPago(), ventaGuardada.getTotal(),
                    "Venta buffet #" + ventaGuardada.getId(), ventaGuardada.getId(), usuarioAutenticado);

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.REGISTRAR_VENTA_BUFFET,
                    ventaGuardada.getId(), true, "Venta registrada por un total de " + total);
            return ventaMapper.mapToResponse(ventaGuardada);
        } catch (EntityNotFoundException | IllegalArgumentException ex) {
            // Acotado a las excepciones de negocio esperadas del bloque (reserva/producto
            // inexistente o inválido, ver resolverReserva/buscarProductoDelEstablecimiento):
            // un catch(RuntimeException) más amplio también auditaría errores de
            // programación genuinos (NPE, ClassCastException) como si fueran un resultado
            // de negocio normal (ver B13 en la auditoría).
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, AccionAuditoria.REGISTRAR_VENTA_BUFFET, null, false, ex.getMessage());
            throw ex;
        }
    }

    private void registrarAuditoriaSiEsEmpleado(Usuario usuario, AccionAuditoria accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
        if (usuario.getRol() == Role.EMPLOYEE) {
            registroAuditoriaService.registrar(usuario, accion, entidadAfectadaId, exitoso, detalle);
        }
    }

    /**
     * Cancela una venta y devuelve el stock vendido a cada producto involucrado.
     * Es idempotente: si ya estaba cancelada, no vuelve a restaurar el stock.
     */
    @Transactional
    public VentaResponse cancelarVenta(Long ventaId, String email) {
        log.info("Iniciando cancelación de venta. ID: {}, Email: {}", ventaId, email);

        Venta venta = ventaRepository.findByIdConDetalles(ventaId)
                .orElseThrow(() -> new EntityNotFoundException("Venta no encontrada"));
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(venta.getEstablecimiento(), email);

        if (venta.getEstado() == EstadoVenta.CANCELADA) {
            log.info("Venta ya se encontraba cancelada. ID: {}", ventaId);
            return ventaMapper.mapToResponse(venta);
        }

        // Mismo lock pesimista que en registrarVenta/ajustarStock (ver A5): serializa la
        // devolución de stock contra cualquier otra venta/ajuste concurrente sobre los
        // mismos productos.
        List<Long> productoIdsOrdenados = venta.getDetalles().stream()
                .map(detalle -> detalle.getProductoBuffet().getId())
                .distinct()
                .sorted()
                .toList();
        productoBuffetRepository.lockPorIds(productoIdsOrdenados);

        for (DetalleVenta detalle : venta.getDetalles()) {
            ProductoBuffet producto = detalle.getProductoBuffet();
            producto.setStock(producto.getStock() + detalle.getCantidad());
            productoBuffetRepository.save(producto);
        }

        venta.setEstado(EstadoVenta.CANCELADA);
        Venta ventaCancelada = ventaRepository.save(venta);
        log.info("Venta cancelada con éxito. ID: {}", ventaId);

        // Revierte el ingreso de caja que generó la venta original (si correspondía), para
        // que el arqueo no reporte un faltante falso tras anular una venta en efectivo (ver
        // M-04 en la auditoría). Solo se compensa si el movimiento original TODAVÍA vive en
        // el turno actualmente abierto: si ese turno ya cerró, escribir la reversión contra
        // el turno abierto ahora ensuciaría su arqueo con un movimiento que no corresponde a
        // ningún billete físico de ESE turno (bug real corregido, ver REVISION_FUNCIONAL.md).
        if (turnoCajaService.movimientoOriginalSigueEnTurnoAbierto(venta.getEstablecimiento(), OrigenMovimientoCaja.VENTA_BUFFET, ventaId)) {
            turnoCajaService.registrarMovimientoSiCorresponde(
                    venta.getEstablecimiento(), TipoMovimientoCaja.EGRESO, OrigenMovimientoCaja.VENTA_BUFFET,
                    venta.getMetodoPago(), venta.getTotal(),
                    "Venta buffet #" + ventaId + " anulada", ventaId, usuarioAutenticado);
        } else {
            log.warn("Venta {} cancelada pero su movimiento de caja original ya no está en el turno abierto: no se ajusta la caja.", ventaId);
        }

        return ventaMapper.mapToResponse(ventaCancelada);
    }

    private Reserva resolverReserva(Long reservaId, Long establecimientoId) {
        if (reservaId == null) {
            return null;
        }
        Reserva reserva = reservaRepository.findById(reservaId)
                .orElseThrow(() -> new EntityNotFoundException("Reserva no encontrada"));
        if (!reserva.getCancha().getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("La reserva no pertenece a este establecimiento");
        }
        if (reserva.getEstado() != EstadoReserva.CONFIRMADA && reserva.getEstado() != EstadoReserva.FINALIZADA) {
            // Ver M19 en la auditoría: sin este chequeo se podía cargar consumo de buffet a
            // una reserva CANCELADA o todavía PENDIENTE_SENA, distorsionando reportes.
            throw new IllegalArgumentException("No se puede cargar consumo a una reserva en estado " + reserva.getEstado());
        }
        return reserva;
    }

    private ProductoBuffet buscarProductoDelEstablecimiento(Long establecimientoId, Long productoId) {
        ProductoBuffet producto = productoBuffetRepository.findById(productoId)
                .orElseThrow(() -> new EntityNotFoundException("Producto no encontrado"));
        if (!producto.getEstablecimiento().getId().equals(establecimientoId)) {
            throw new IllegalArgumentException("El producto no pertenece a este establecimiento");
        }
        return producto;
    }

}
