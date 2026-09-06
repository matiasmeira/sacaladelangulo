package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
