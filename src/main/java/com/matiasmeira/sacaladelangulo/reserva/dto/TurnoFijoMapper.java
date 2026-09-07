package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TurnoFijoMapper {

    private final ReservaMapper reservaMapper;

    public TurnoFijoResponse mapToResponse(TurnoFijo turnoFijo, List<ReservaResponse> ocurrencias) {
        Usuario jugador = turnoFijo.getJugador();
        return new TurnoFijoResponse(
                turnoFijo.getId(),
                turnoFijo.getCancha().getId(),
                turnoFijo.getCancha().getNombre(),
                turnoFijo.getDeporteSeleccionado(),
                turnoFijo.getDiaSemana(),
                turnoFijo.getHoraInicio(),
                turnoFijo.getHoraFin(),
                turnoFijo.getFechaInicioPeriodo(),
                turnoFijo.getFechaFinPeriodo(),
                turnoFijo.getEstado().name(),
                turnoFijo.getCanceladoDesde(),
                jugador != null ? jugador.getId() : null,
                jugador != null ? jugador.getNombre() : null,
                turnoFijo.getNombreClienteManual(),
                turnoFijo.getTelefonoClienteManual(),
                turnoFijo.getRenovadoDesdeId(),
                ocurrencias
        );
    }

    /** Para el listado: la regla sin sus ocurrencias. */
    public TurnoFijoResponse mapToResponse(TurnoFijo turnoFijo) {
        return mapToResponse(turnoFijo, List.of());
    }

    /**
     * Para el listado paginado: la regla sin ocurrencias más los dos agregados de la fila
     * que le corresponde en la consulta de {@code ReservaRepository.agregadosPorTurnoFijo}
     * (columnas: id, COUNT(r), MIN(fechaHoraInicio)). {@code fila} es null cuando la serie
     * no tiene ninguna ocurrencia futura viva: en ese caso van 0 y null.
     */
    public TurnoFijoListadoResponse mapToListado(TurnoFijo turnoFijo, Object[] fila) {
        TurnoFijoResponse base = mapToResponse(turnoFijo);
        long ocurrenciasActivas = fila != null ? (Long) fila[1] : 0L;
        LocalDateTime proximaOcurrencia = fila != null ? (LocalDateTime) fila[2] : null;
        return new TurnoFijoListadoResponse(
                base.id(),
                base.canchaId(),
                base.canchaNombre(),
                base.deporteSeleccionado(),
                base.diaSemana(),
                base.horaInicio(),
                base.horaFin(),
                base.fechaInicioPeriodo(),
                base.fechaFinPeriodo(),
                base.estado(),
                base.canceladoDesde(),
                base.jugadorId(),
                base.jugadorNombre(),
                base.nombreClienteManual(),
                base.telefonoClienteManual(),
                base.renovadoDesdeId(),
                ocurrenciasActivas,
                proximaOcurrencia
        );
    }
}
