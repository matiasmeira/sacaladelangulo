package com.matiasmeira.sacaladelangulo.cierrecaja.dto;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.MovimientoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TurnoCajaMapper {

    private final MovimientoCajaMapper movimientoCajaMapper;

    public TurnoCajaResponse mapToResponse(TurnoCaja turno) {
        return new TurnoCajaResponse(
                turno.getId(),
                turno.getEstablecimiento().getId(),
                turno.getDispositivoCaja() != null ? turno.getDispositivoCaja().getId() : null,
                turno.getDispositivoCaja() != null ? turno.getDispositivoCaja().getLabel() : null,
                turno.getUsuarioApertura().getId(),
                turno.getUsuarioApertura().getNombre(),
                turno.getFechaApertura(),
                turno.getFondoInicial(),
                turno.getEstado().name(),
                turno.getFechaCierre(),
                turno.getUsuarioCierre() != null ? turno.getUsuarioCierre().getId() : null,
                turno.getUsuarioCierre() != null ? turno.getUsuarioCierre().getNombre() : null,
                turno.getSaldoTeoricoEfectivo(),
                turno.getSaldoRealContado(),
                turno.getDiferencia(),
                turno.getObservaciones()
        );
    }

    public TurnoCajaResumenResponse mapToResumen(TurnoCaja turno) {
        return new TurnoCajaResumenResponse(
                turno.getId(),
                turno.getEstablecimiento().getId(),
                turno.getFechaApertura(),
                turno.getFechaCierre(),
                turno.getEstado().name(),
                turno.getFondoInicial(),
                turno.getSaldoRealContado(),
                turno.getDiferencia(),
                turno.getUsuarioApertura().getNombre()
        );
    }

    public TurnoCajaDetalleResponse mapToDetalle(TurnoCaja turno, List<MovimientoCaja> movimientos) {
        return new TurnoCajaDetalleResponse(
                mapToResponse(turno),
                movimientos.stream().map(movimientoCajaMapper::mapToResponse).toList()
        );
    }

    public CierreCajaResponse mapToCierre(TurnoCaja turno) {
        BigDecimal diferencia = turno.getDiferencia();
        String resultado;
        if (diferencia.signum() > 0) {
            resultado = "SOBRANTE";
        } else if (diferencia.signum() < 0) {
            resultado = "FALTANTE";
        } else {
            resultado = "EXACTO";
        }

        return new CierreCajaResponse(
                turno.getId(),
                turno.getEstablecimiento().getId(),
                turno.getFechaCierre(),
                turno.getFondoInicial(),
                turno.getSaldoTeoricoEfectivo(),
                turno.getSaldoRealContado(),
                diferencia,
                resultado,
                turno.getObservaciones()
        );
    }
}
