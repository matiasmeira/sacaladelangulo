package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.DesglosePorMetodoPagoDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.FacturacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.PuntoFacturacionDiariaDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reserva.model.MetodoPago;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Reporte de facturación: total facturado, desglose por método de pago real (ver Javadoc de
 * ReservaRepository.sumFacturacionPorMetodoPago sobre por qué no hay distinción
 * online/mostrador confiable) y serie temporal diaria. Solo cuenta reservas FINALIZADA.
 */
@Service
@RequiredArgsConstructor
public class ReporteFacturacionService {

    private final ReservaRepository reservaRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;

    @Transactional(readOnly = true)
    public FacturacionReporteResponse obtenerFacturacion(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        Map<MetodoPago, BigDecimal> montoActual = montoPorMetodoPago(establecimientoId, desde, hasta);
        Map<MetodoPago, Long> cantidadActual = cantidadPorMetodoPago(establecimientoId, desde, hasta);
        Map<MetodoPago, BigDecimal> montoAnterior = montoPorMetodoPago(establecimientoId, anterior.desde(), anterior.hasta());
        Map<MetodoPago, Long> cantidadAnterior = cantidadPorMetodoPago(establecimientoId, anterior.desde(), anterior.hasta());

        BigDecimal totalActual = montoActual.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAnterior = montoAnterior.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        List<DesglosePorMetodoPagoDto> desglose = EnumSet.allOf(MetodoPago.class).stream()
                .filter(metodo -> montoActual.containsKey(metodo) || montoAnterior.containsKey(metodo))
                .map(metodo -> new DesglosePorMetodoPagoDto(
                        metodo,
                        new Comparativo<>(montoActual.getOrDefault(metodo, BigDecimal.ZERO), montoAnterior.getOrDefault(metodo, BigDecimal.ZERO)),
                        new Comparativo<>(cantidadActual.getOrDefault(metodo, 0L), cantidadAnterior.getOrDefault(metodo, 0L))))
                .toList();

        List<PuntoFacturacionDiariaDto> serieTemporal = armarSerieTemporal(establecimientoId, desde, hasta, anterior);

        return new FacturacionReporteResponse(
                new RangoFechas(desde, hasta),
                anterior,
                new Comparativo<>(totalActual, totalAnterior),
                desglose,
                serieTemporal
        );
    }

    private Map<MetodoPago, BigDecimal> montoPorMetodoPago(Long establecimientoId, LocalDate desde, LocalDate hasta) {
        Map<MetodoPago, BigDecimal> resultado = new EnumMap<>(MetodoPago.class);
        for (Object[] fila : reservaRepository.sumFacturacionPorMetodoPago(establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta))) {
            MetodoPago metodo = (MetodoPago) fila[0];
            if (metodo != null) {
                resultado.put(metodo, (BigDecimal) fila[1]);
            }
        }
        return resultado;
    }

    private Map<MetodoPago, Long> cantidadPorMetodoPago(Long establecimientoId, LocalDate desde, LocalDate hasta) {
        Map<MetodoPago, Long> resultado = new EnumMap<>(MetodoPago.class);
        for (Object[] fila : reservaRepository.sumFacturacionPorMetodoPago(establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta))) {
            MetodoPago metodo = (MetodoPago) fila[0];
            if (metodo != null) {
                resultado.put(metodo, (Long) fila[2]);
            }
        }
        return resultado;
    }

    private List<PuntoFacturacionDiariaDto> armarSerieTemporal(Long establecimientoId, LocalDate desde, LocalDate hasta, RangoFechas anterior) {
        Map<LocalDate, BigDecimal> porDiaActual = agruparPorDia(reservaRepository.findFechaYPrecioParaSerieTemporal(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta)));
        Map<LocalDate, BigDecimal> porDiaAnterior = agruparPorDia(reservaRepository.findFechaYPrecioParaSerieTemporal(
                establecimientoId, PeriodoUtil.inicioDelDia(anterior.desde()), PeriodoUtil.finDelDia(anterior.hasta())));

        long dias = java.time.temporal.ChronoUnit.DAYS.between(desde, hasta) + 1;
        List<PuntoFacturacionDiariaDto> serie = new java.util.ArrayList<>();
        for (int i = 0; i < dias; i++) {
            LocalDate fechaActual = desde.plusDays(i);
            LocalDate fechaAnterior = anterior.desde().plusDays(i);
            serie.add(new PuntoFacturacionDiariaDto(
                    i,
                    fechaActual, porDiaActual.getOrDefault(fechaActual, BigDecimal.ZERO),
                    fechaAnterior, porDiaAnterior.getOrDefault(fechaAnterior, BigDecimal.ZERO)));
        }
        return serie;
    }

    private Map<LocalDate, BigDecimal> agruparPorDia(List<Object[]> filas) {
        Map<LocalDate, BigDecimal> porDia = new java.util.HashMap<>();
        for (Object[] fila : filas) {
            LocalDateTime fechaHora = (LocalDateTime) fila[0];
            BigDecimal precio = (BigDecimal) fila[1];
            porDia.merge(fechaHora.toLocalDate(), precio, BigDecimal::add);
        }
        return porDia;
    }
}
