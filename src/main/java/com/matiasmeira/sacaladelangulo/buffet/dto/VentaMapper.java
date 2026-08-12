package com.matiasmeira.sacaladelangulo.buffet.dto;

import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class VentaMapper {

    public VentaResponse mapToResponse(Venta venta) {
        return new VentaResponse(
                venta.getId(),
                venta.getFechaHora(),
                venta.getTotal(),
                venta.getEstado().name(),
                venta.getMetodoPago().name(),
                venta.getEstablecimiento().getId(),
                venta.getReserva() != null ? venta.getReserva().getId() : null,
                venta.getDetalles().stream().map(this::mapDetalleToResponse).toList()
        );
    }

    public VentaResumenResponse mapToResumenResponse(Venta venta) {
        return new VentaResumenResponse(
                venta.getId(),
                venta.getFechaHora(),
                venta.getTotal(),
                venta.getEstado().name(),
                venta.getMetodoPago().name(),
                venta.getReserva() != null ? venta.getReserva().getId() : null
        );
    }

    /**
     * El precio unitario se deriva de subtotal/cantidad (no del precio actual del
     * producto) para que el detalle de una venta vieja siga reflejando el precio
     * al que efectivamente se vendió, aunque el producto haya cambiado de precio después.
     */
    private DetalleVentaResponse mapDetalleToResponse(DetalleVenta detalle) {
        BigDecimal precioUnitario = detalle.getSubtotal()
                .divide(BigDecimal.valueOf(detalle.getCantidad()), 2, RoundingMode.HALF_UP);

        return new DetalleVentaResponse(
                detalle.getId(),
                detalle.getProductoBuffet().getId(),
                detalle.getProductoBuffet().getNombre(),
                detalle.getCantidad(),
                precioUnitario,
                detalle.getSubtotal()
        );
    }
}
