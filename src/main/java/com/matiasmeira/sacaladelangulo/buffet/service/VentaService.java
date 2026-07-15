package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.dto.DetalleVentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.MetricasVentasResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoMasVendidoResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final UsuarioRepository usuarioRepository;
    private final VentaMapper ventaMapper;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;

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

            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.REGISTRAR_VENTA_BUFFET,
                    ventaGuardada.getId(), true, "Venta registrada por un total de " + total);
            return ventaMapper.mapToResponse(ventaGuardada);
        } catch (RuntimeException ex) {
            registrarAuditoriaSiEsEmpleado(usuarioAutenticado, PermisoEmpleado.REGISTRAR_VENTA_BUFFET, null, false, ex.getMessage());
            throw ex;
        }
    }

    private void registrarAuditoriaSiEsEmpleado(Usuario usuario, PermisoEmpleado accion, Long entidadAfectadaId, boolean exitoso, String detalle) {
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
        validarPropietarioOAdmin(venta.getEstablecimiento(), email);

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

        return ventaMapper.mapToResponse(ventaCancelada);
    }

    /**
     * Métricas de ventas del buffet en un rango de fechas (inclusive): ingreso total,
     * cantidad de ventas, ticket promedio y ranking de productos más vendidos por
     * cantidad. Solo se consideran ventas CONFIRMADA; las canceladas no suman.
     */
    @Transactional(readOnly = true)
    public MetricasVentasResponse obtenerMetricas(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        validarPropietarioOAdmin(establecimiento, email);

        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }

        List<Venta> ventas = ventaRepository.findByEstablecimientoIdAndEstadoAndFechaHoraBetween(
                establecimientoId, EstadoVenta.CONFIRMADA, desde.atStartOfDay(), hasta.atTime(LocalTime.MAX));

        BigDecimal ingresoTotal = ventas.stream()
                .map(Venta::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long cantidadVentas = ventas.size();
        BigDecimal ticketPromedio = cantidadVentas > 0
                ? ingresoTotal.divide(BigDecimal.valueOf(cantidadVentas), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new MetricasVentasResponse(
                establecimientoId, desde, hasta, ingresoTotal, cantidadVentas, ticketPromedio,
                calcularProductosMasVendidos(ventas));
    }

    private List<ProductoMasVendidoResponse> calcularProductosMasVendidos(List<Venta> ventas) {
        record Acumulado(String nombre, long cantidad, BigDecimal ingreso) {
            Acumulado sumar(String nombreProducto, long cantidadVendida, BigDecimal subtotal) {
                return new Acumulado(nombreProducto, cantidad + cantidadVendida, ingreso.add(subtotal));
            }
        }

        Map<Long, Acumulado> acumuladoPorProducto = new LinkedHashMap<>();
        for (Venta venta : ventas) {
            for (DetalleVenta detalle : venta.getDetalles()) {
                Long productoId = detalle.getProductoBuffet().getId();
                Acumulado previo = acumuladoPorProducto.getOrDefault(
                        productoId, new Acumulado(detalle.getProductoBuffet().getNombre(), 0, BigDecimal.ZERO));
                acumuladoPorProducto.put(productoId,
                        previo.sumar(detalle.getProductoBuffet().getNombre(), detalle.getCantidad(), detalle.getSubtotal()));
            }
        }

        return acumuladoPorProducto.entrySet().stream()
                .map(entry -> new ProductoMasVendidoResponse(
                        entry.getKey(), entry.getValue().nombre(), entry.getValue().cantidad(), entry.getValue().ingreso()))
                .sorted(Comparator.comparingLong(ProductoMasVendidoResponse::cantidadVendida).reversed())
                .toList();
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

    private void validarPropietarioOAdmin(Establecimiento establecimiento, String email) {
        Usuario usuarioAutenticado = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        if (usuarioAutenticado.getRol() != Role.ADMIN &&
                !establecimiento.getDueno().getId().equals(usuarioAutenticado.getId())) {
            throw new AccessDeniedException("No autorizado en este establecimiento");
        }
    }
}
