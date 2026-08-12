package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.buffet.dto.MetricasVentasResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoMasVendidoResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResumenResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporting/métricas de ventas del buffet, separado de VentaService (que se ocupa
 * exclusivamente del ciclo de vida de la venta: registrar/cancelar) para no mezclar
 * ambas responsabilidades en una sola clase (ver B12 en la auditoría).
 */
@Service
@RequiredArgsConstructor
public class VentaMetricasService {

    private final VentaRepository ventaRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final VentaMapper ventaMapper;

    /**
     * Métricas de ventas del buffet en un rango de fechas (inclusive): ingreso total,
     * cantidad de ventas, ticket promedio y ranking de productos más vendidos por
     * cantidad. Solo se consideran ventas CONFIRMADA; las canceladas no suman.
     */
    @Transactional(readOnly = true)
    public MetricasVentasResponse obtenerMetricas(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

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

    /**
     * Listado paginado de ventas de buffet de un establecimiento en un rango de
     * fechas (inclusive), para la tabla del front — sin desglose de ítems (ver
     * VentaResumenResponse). Mismo criterio de autorización y de rango de fechas
     * que obtenerMetricas.
     */
    @Transactional(readOnly = true)
    public Page<VentaResumenResponse> listarVentas(Long establecimientoId, LocalDate desde, LocalDate hasta,
                                                     EstadoVenta estado, String email, Pageable pageable) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }

        return ventaRepository.buscarPaginado(establecimientoId, estado, desde.atStartOfDay(), hasta.atTime(LocalTime.MAX), pageable)
                .map(ventaMapper::mapToResumenResponse);
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

}
