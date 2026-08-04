package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import org.springframework.stereotype.Component;

@Component
public class MovimientoCajaMapper {

    public MovimientoCajaResponse mapToResponse(MovimientoCaja movimiento) {
        return new MovimientoCajaResponse(
                movimiento.getId(),
                movimiento.getTurnoCaja().getId(),
                movimiento.getTipo().name(),
                movimiento.getOrigen().name(),
                movimiento.getMetodoPago().name(),
                movimiento.getMonto(),
                movimiento.getDescripcion(),
                movimiento.getReferenciaId(),
                movimiento.getFechaHora(),
                movimiento.getUsuario().getId(),
                movimiento.getUsuario().getNombre()
        );
    }
}
