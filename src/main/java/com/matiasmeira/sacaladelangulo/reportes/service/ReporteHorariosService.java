package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.reportes.dto.HorarioPedidoDto;
import com.matiasmeira.sacaladelangulo.reportes.dto.HorariosPedidosReporteResponse;
import com.matiasmeira.sacaladelangulo.reportes.dto.RangoFechas;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranking de combinaciones día de semana + hora con más reservas FINALIZADA. Se agrupa en
 * memoria (ver Javadoc de ReservaRepository.findFechasParaHorariosPedidos sobre por qué no
 * es un GROUP BY JPQL portable) sobre una proyección liviana de una sola columna. No compara
 * con el período anterior (decisión explícita: un ranking comparado entre dos períodos es
 * una feature de UI que no se pidió).
 */
@Service
@RequiredArgsConstructor
public class ReporteHorariosService {

    private final ReservaRepository reservaRepository;
    private final ReporteAutorizacionService reporteAutorizacionService;

    private record ClaveHorario(DayOfWeek diaSemana, int hora) {
    }

    @Transactional(readOnly = true)
    public HorariosPedidosReporteResponse obtenerHorariosPedidos(Long establecimientoId, LocalDate desde, LocalDate hasta, int topN, String email) {
        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }
        reporteAutorizacionService.validarDuenoDelEstablecimiento(establecimientoId, email);

        List<LocalDateTime> fechas = reservaRepository.findFechasParaHorariosPedidos(
                establecimientoId, PeriodoUtil.inicioDelDia(desde), PeriodoUtil.finDelDia(hasta));

        Map<ClaveHorario, Long> cantidadPorClave = new LinkedHashMap<>();
        for (LocalDateTime fecha : fechas) {
            ClaveHorario clave = new ClaveHorario(fecha.getDayOfWeek(), fecha.getHour());
            cantidadPorClave.merge(clave, 1L, Long::sum);
        }

        List<HorarioPedidoDto> ranking = cantidadPorClave.entrySet().stream()
                .sorted(Comparator.<Map.Entry<ClaveHorario, Long>>comparingLong(Map.Entry::getValue).reversed())
                .limit(topN)
                .map(entry -> new HorarioPedidoDto(entry.getKey().diaSemana(), entry.getKey().hora(), entry.getValue()))
                .toList();

        return new HorariosPedidosReporteResponse(new RangoFechas(desde, hasta), ranking);
    }
}
