package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.reportes.dto.AusenciasInfo;
import com.matiasmeira.sacaladelangulo.reportes.dto.ClientesReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.Comparativo;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reportes.dto.TopClienteDto;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reporte de clientes: clientes nuevos (primera reserva FINALIZADA de ese jugador *en este
 * establecimiento*, no Usuario.fechaCreacion, que es de toda la plataforma), ausencias
 * (cantidad de reservas AUSENTE del establecimiento en el rango, y por jugador dentro del top
 * de clientes, ver EstadoReserva.AUSENTE) y top clientes por cantidad de reservas en el rango
 * (sin comparar con el período anterior, mismo criterio que horarios-pedidos).
 */
@Service
@RequiredArgsConstructor
public class ReporteClientesService {

    private final ReservaRepository reservaRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;

    @Transactional(readOnly = true)
    public ClientesReporteResponse obtenerClientes(Long establecimientoId, LocalDate desde, LocalDate hasta, int topN, String email) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        RangoFechas anterior = PeriodoUtil.periodoAnterior(desde, hasta);

        long clientesNuevosActual = 0;
        long clientesNuevosAnterior = 0;
        for (Object[] fila : reservaRepository.primeraReservaPorJugador(establecimientoId)) {
            LocalDate primeraFecha = ((LocalDateTime) fila[1]).toLocalDate();
            if (!primeraFecha.isBefore(desde) && !primeraFecha.isAfter(hasta)) {
                clientesNuevosActual++;
            } else if (!primeraFecha.isBefore(anterior.desde()) && !primeraFecha.isAfter(anterior.hasta())) {
                clientesNuevosAnterior++;
            }
        }

        List<Object[]> filasTopClientes = reservaRepository.topClientesPorReservas(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta), PageRequest.of(0, topN));

        List<Long> jugadorIds = filasTopClientes.stream().map(fila -> (Long) fila[0]).toList();
        Map<Long, Long> ausenciasPorJugador = new HashMap<>();
        if (!jugadorIds.isEmpty()) {
            for (Object[] fila : reservaRepository.countAusenciasPorJugadoresEnRango(
                    establecimientoId, jugadorIds, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta))) {
                ausenciasPorJugador.put((Long) fila[0], ((Number) fila[1]).longValue());
            }
        }

        List<TopClienteDto> topClientes = filasTopClientes.stream()
                .map(fila -> new TopClienteDto((Long) fila[0], (String) fila[1], ((Number) fila[2]).longValue(),
                        ausenciasPorJugador.getOrDefault((Long) fila[0], 0L)))
                .toList();

        long totalAusencias = reservaRepository.countAusenciasEnRango(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta));

        return new ClientesReporteResponse(
                new RangoFechas(desde, hasta),
                anterior,
                new Comparativo<>(clientesNuevosActual, clientesNuevosAnterior),
                new AusenciasInfo(true, totalAusencias, null),
                topClientes
        );
    }
}
