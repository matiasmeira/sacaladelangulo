package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.stereotype.Component;

@Component
public class ReservaMapper {

    public ReservaResponse mapToResponse(Reserva reserva) {
        return new ReservaResponse(
                reserva.getId(),
                reserva.getJugador().getId(),
                reserva.getJugador().getNombre(),
                reserva.getCancha().getId(),
                reserva.getCancha().getNombre(),
                reserva.getFechaHoraInicio(),
                reserva.getFechaHoraFin(),
                reserva.getEstado().name(),
                reserva.getPrecioTotal(),
                reserva.getSenaPagada()
        );
    }
}
