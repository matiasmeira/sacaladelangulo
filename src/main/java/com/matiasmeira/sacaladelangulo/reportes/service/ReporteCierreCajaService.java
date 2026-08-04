package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.CierreCajaReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reportes.dto.TurnoCajaResumenDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Reporte de diferencias de caja: lista los turnos CERRADO de un establecimiento en un rango
 * de fechas (por fechaCierre) con su diferencia individual, y acumula faltante/sobrante del
 * período — espejo de ReporteGastosService para el lado de los descuadres de caja. A
 * diferencia de los demás reportes del módulo, no compara con el período anterior: es un
 * listado + acumulados, no una métrica escalar comparable (mismo criterio que
 * ReporteHorariosService/ReporteClientesService para sus rankings).
 */
@Service
@RequiredArgsConstructor
public class ReporteCierreCajaService {

    private final TurnoCajaRepository turnoCajaRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;

    @Transactional(readOnly = true)
    public CierreCajaReporteResponse obtenerDiferenciasDeCaja(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        validarRango(desde, hasta);
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        List<TurnoCaja> turnosCerrados = turnoCajaRepository.findByEstablecimientoIdAndEstadoAndFechaCierreBetween(
                establecimientoId, EstadoTurnoCaja.CERRADO, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta));

        List<TurnoCajaResumenDto> turnos = turnosCerrados.stream()
                .map(turno -> new TurnoCajaResumenDto(
                        turno.getId(),
                        turno.getFechaApertura(),
                        turno.getFechaCierre(),
                        turno.getSaldoTeoricoEfectivo(),
                        turno.getSaldoRealContado(),
                        turno.getDiferencia()))
                .toList();

        BigDecimal faltanteAcumulado = turnosCerrados.stream()
                .map(TurnoCaja::getDiferencia)
                .filter(diferencia -> diferencia != null && diferencia.signum() < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal sobranteAcumulado = turnosCerrados.stream()
                .map(TurnoCaja::getDiferencia)
                .filter(diferencia -> diferencia != null && diferencia.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CierreCajaReporteResponse(new RangoFechas(desde, hasta), turnos, faltanteAcumulado, sobranteAcumulado);
    }

    private void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
    }
}
