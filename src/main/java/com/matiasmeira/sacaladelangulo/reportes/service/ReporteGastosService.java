package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.gastos.model.CategoriaGasto;
import com.matiasmeira.sacaladelangulo.gastos.repository.GastoRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.DesglosePorCategoriaDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.GastosReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.PuntoGastoDiariaDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reportes.dto.ResultadoReporteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte de gastos: total gastado, desglose por categoría y serie temporal diaria — espejo
 * de ReporteFacturacionService para el lado de los egresos. A diferencia de la facturación
 * (que usa Reserva.fechaHora, un LocalDateTime), Gasto.fecha ya es LocalDate, así que las
 * queries comparan fechas directo sin pasar por PeriodoUtil.inicioDelDia/finDelDia.
 */
@Service
@RequiredArgsConstructor
public class ReporteGastosService {

    private final GastoRepository gastoRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;
    private final ReporteFacturacionService reporteFacturacionService;

    @Transactional(readOnly = true)
    public GastosReporteResponse obtenerGastos(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        validarRango(desde, hasta);
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        Map<CategoriaGasto, BigDecimal> montoActual = montoPorCategoria(establecimientoId, desde, hasta);
        Map<CategoriaGasto, Long> cantidadActual = cantidadPorCategoria(establecimientoId, desde, hasta);
        Map<CategoriaGasto, BigDecimal> montoAnterior = montoPorCategoria(establecimientoId, anterior.desde(), anterior.hasta());
        Map<CategoriaGasto, Long> cantidadAnterior = cantidadPorCategoria(establecimientoId, anterior.desde(), anterior.hasta());

        Comparativo<BigDecimal> total = new Comparativo<>(
                sumar(montoActual.values()),
                sumar(montoAnterior.values())
        );

        List<DesglosePorCategoriaDto> desglose = EnumSet.allOf(CategoriaGasto.class).stream()
                .filter(categoria -> montoActual.containsKey(categoria) || montoAnterior.containsKey(categoria))
                .map(categoria -> new DesglosePorCategoriaDto(
                        categoria,
                        new Comparativo<>(montoActual.getOrDefault(categoria, BigDecimal.ZERO), montoAnterior.getOrDefault(categoria, BigDecimal.ZERO)),
                        new Comparativo<>(cantidadActual.getOrDefault(categoria, 0L), cantidadAnterior.getOrDefault(categoria, 0L))))
                .toList();

        List<PuntoGastoDiariaDto> serieTemporal = armarSerieTemporal(establecimientoId, desde, hasta, anterior);

        return new GastosReporteResponse(
                new RangoFechas(desde, hasta),
                anterior,
                total,
                desglose,
                serieTemporal
        );
    }

    @Transactional(readOnly = true)
    public ResultadoReporteResponse obtenerResultado(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        validarRango(desde, hasta);
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        Comparativo<BigDecimal> totalFacturado = reporteFacturacionService.obtenerFacturacion(establecimientoId, desde, hasta, email).totalFacturado();
        Comparativo<BigDecimal> totalGastos = new Comparativo<>(
                sumar(montoPorCategoria(establecimientoId, desde, hasta).values()),
                sumar(montoPorCategoria(establecimientoId, anterior.desde(), anterior.hasta()).values())
        );

        Comparativo<BigDecimal> neto = new Comparativo<>(
                totalFacturado.actual().subtract(totalGastos.actual()),
                totalFacturado.anterior().subtract(totalGastos.anterior())
        );

        return new ResultadoReporteResponse(
                new RangoFechas(desde, hasta),
                anterior,
                totalFacturado,
                totalGastos,
                neto
        );
    }

    private void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
    }

    private BigDecimal sumar(java.util.Collection<BigDecimal> valores) {
        return valores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<CategoriaGasto, BigDecimal> montoPorCategoria(Long establecimientoId, LocalDate desde, LocalDate hasta) {
        Map<CategoriaGasto, BigDecimal> resultado = new EnumMap<>(CategoriaGasto.class);
        for (Object[] fila : gastoRepository.sumGastoPorCategoria(establecimientoId, desde, hasta)) {
            CategoriaGasto categoria = (CategoriaGasto) fila[0];
            if (categoria != null) {
                resultado.put(categoria, (BigDecimal) fila[1]);
            }
        }
        return resultado;
    }

    private Map<CategoriaGasto, Long> cantidadPorCategoria(Long establecimientoId, LocalDate desde, LocalDate hasta) {
        Map<CategoriaGasto, Long> resultado = new EnumMap<>(CategoriaGasto.class);
        for (Object[] fila : gastoRepository.sumGastoPorCategoria(establecimientoId, desde, hasta)) {
            CategoriaGasto categoria = (CategoriaGasto) fila[0];
            if (categoria != null) {
                resultado.put(categoria, (Long) fila[2]);
            }
        }
        return resultado;
    }

    private List<PuntoGastoDiariaDto> armarSerieTemporal(Long establecimientoId, LocalDate desde, LocalDate hasta, RangoFechas anterior) {
        Map<LocalDate, BigDecimal> porDiaActual = agruparPorDia(gastoRepository.findFechaYMontoParaSerieTemporal(establecimientoId, desde, hasta));
        Map<LocalDate, BigDecimal> porDiaAnterior = agruparPorDia(gastoRepository.findFechaYMontoParaSerieTemporal(establecimientoId, anterior.desde(), anterior.hasta()));

        long dias = ChronoUnit.DAYS.between(desde, hasta) + 1;
        List<PuntoGastoDiariaDto> serie = new ArrayList<>();
        for (int i = 0; i < dias; i++) {
            LocalDate fechaActual = desde.plusDays(i);
            LocalDate fechaAnterior = anterior.desde().plusDays(i);
            serie.add(new PuntoGastoDiariaDto(
                    i,
                    fechaActual, porDiaActual.getOrDefault(fechaActual, BigDecimal.ZERO),
                    fechaAnterior, porDiaAnterior.getOrDefault(fechaAnterior, BigDecimal.ZERO)));
        }
        return serie;
    }

    private Map<LocalDate, BigDecimal> agruparPorDia(List<Object[]> filas) {
        Map<LocalDate, BigDecimal> porDia = new HashMap<>();
        for (Object[] fila : filas) {
            LocalDate fecha = (LocalDate) fila[0];
            BigDecimal monto = (BigDecimal) fila[1];
            porDia.merge(fecha, monto, BigDecimal::add);
        }
        return porDia;
    }
}
