package com.matiasmeira.sacaladelangulo.gastos.dto;

import com.matiasmeira.sacaladelangulo.gastos.model.Gasto;
import org.springframework.stereotype.Component;

@Component
public class GastoMapper {

    public GastoResponse mapToResponse(Gasto gasto) {
        return new GastoResponse(
                gasto.getId(),
                gasto.getEstablecimiento().getId(),
                gasto.getFecha(),
                gasto.getMonto(),
                gasto.getCategoria().name(),
                gasto.getDescripcion(),
                gasto.getMetodoPago().name(),
                gasto.getComprobanteUrl(),
                gasto.getUsuarioRegistro().getId(),
                gasto.getUsuarioRegistro().getNombre(),
                gasto.getFechaCreacion()
        );
    }
}
