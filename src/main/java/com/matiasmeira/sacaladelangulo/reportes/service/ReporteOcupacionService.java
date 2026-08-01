package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.FranjaHoraria;
import com.matiasmeira.sacaladelangulo.reportes.dto.OcupacionPorCanchaDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.OcupacionPorFranjaDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.OcupacionReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaOcupacionProjection;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reporte de % de ocupación. Ver Javadoc de OcupacionCalculator para el detalle del
 * algoritmo. Aproximación documentada explícitamente (ver notaMetodologica en la
 * respuesta): usa las canchas activas HOY para todo el rango pedido (incluido el período
 * anterior), porque Cancha.isActive no tiene historial.
 */
@Service
@RequiredArgsConstructor
public class ReporteOcupacionService {

    private static final String NOTA_METODOLOGICA =
            "Calculado sobre el horario de atención del establecimiento, excluyendo días no " +
            "laborables y canchas en mantenimiento (bloqueos). Las horas disponibles usan las " +
            "canchas activas al momento de generar el reporte, aplicadas a todo el rango pedido " +
            "(incluido el período anterior): si una cancha se dio de alta o de baja durante el " +
            "rango, el % puede quedar levemente distorsionado, ya que no hay historial de " +
            "activación/desactivación de canchas.";

    private final ReservaRepository reservaRepository;
    private final CanchaRepository canchaRepository;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;

    @Transactional(readOnly = true)
    public OcupacionReporteResponse obtenerOcupacion(Long establecimientoId, LocalDate desde, LocalDate hasta, String email) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
        Establecimiento establecimiento = reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        List<OcupacionCalculator.CanchaInfo> canchasActivas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId).stream()
                .map(c -> new OcupacionCalculator.CanchaInfo(c.getId(), c.getNombre()))
                .toList();

        OcupacionCalculator.Resultado resultadoActual = calcularPeriodo(establecimiento, establecimientoId, desde, hasta, canchasActivas);
        OcupacionCalculator.Resultado resultadoAnterior = calcularPeriodo(establecimiento, establecimientoId, anterior.desde(), anterior.hasta(), canchasActivas);

        List<OcupacionPorFranjaDto> porFranja = List.of(FranjaHoraria.values()).stream()
                .map(franja -> {
                    var actual = resultadoActual.porFranja().get(franja);
                    var previo = resultadoAnterior.porFranja().get(franja);
                    return new OcupacionPorFranjaDto(
                            franja,
                            new Comparativo<>(porcentaje(actual.reservadas(), actual.disponibles()), porcentaje(previo.reservadas(), previo.disponibles())),
                            new Comparativo<>(actual.reservadas(), previo.reservadas()),
                            new Comparativo<>(actual.disponibles(), previo.disponibles()));
                })
                .toList();

        List<OcupacionPorCanchaDto> porCancha = canchasActivas.stream()
                .map(cancha -> {
                    var actual = resultadoActual.porCancha().get(cancha.id());
                    var previo = resultadoAnterior.porCancha().get(cancha.id());
                    return new OcupacionPorCanchaDto(
                            cancha.id(),
                            cancha.nombre(),
                            new Comparativo<>(porcentaje(actual.reservadas(), actual.disponibles()), porcentaje(previo.reservadas(), previo.disponibles())),
                            new Comparativo<>(actual.reservadas(), previo.reservadas()),
                            new Comparativo<>(actual.disponibles(), previo.disponibles()));
                })
                .toList();

        return new OcupacionReporteResponse(
                new RangoFechas(desde, hasta),
                anterior,
                new Comparativo<>(
                        porcentaje(resultadoActual.horasReservadas(), resultadoActual.horasDisponibles()),
                        porcentaje(resultadoAnterior.horasReservadas(), resultadoAnterior.horasDisponibles())),
                porFranja,
                porCancha,
                NOTA_METODOLOGICA
        );
    }

    private OcupacionCalculator.Resultado calcularPeriodo(Establecimiento establecimiento, Long establecimientoId, LocalDate desde, LocalDate hasta, List<OcupacionCalculator.CanchaInfo> canchasActivas) {
        java.util.Set<LocalDate> diasNoLaborables = diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(establecimientoId, desde, hasta).stream()
                .map(com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable::getFecha)
                .collect(Collectors.toSet());

        List<BloqueoCancha> bloqueosEntidad = bloqueoCanchaRepository.findByEstablecimientoAndRango(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta));
        List<OcupacionCalculator.BloqueoProyeccion> bloqueos = bloqueosEntidad.stream()
                .map(b -> new OcupacionCalculator.BloqueoProyeccion(b.getCancha().getId(), b.getFechaInicio(), b.getFechaFin()))
                .toList();

        List<ReservaOcupacionProjection> proyeccionReservas = reservaRepository.findProyeccionOcupacion(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta));
        List<OcupacionCalculator.ReservaProyeccion> reservas = proyeccionReservas.stream()
                .map(r -> new OcupacionCalculator.ReservaProyeccion(r.getCanchaId(), r.getFechaHoraInicio(), r.getFechaHoraFin()))
                .toList();

        return OcupacionCalculator.calcular(desde, hasta, establecimiento.getHorariosAtencion(), diasNoLaborables, canchasActivas, bloqueos, reservas);
    }

    private BigDecimal porcentaje(BigDecimal reservadas, BigDecimal disponibles) {
        if (disponibles.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return reservadas.multiply(BigDecimal.valueOf(100)).divide(disponibles, 2, RoundingMode.HALF_UP);
    }
}
