package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.springframework.stereotype.Component;

@Component
public class ReservaMapper {

    public ReservaResponse mapToResponse(Reserva reserva) {
        Usuario jugador = reserva.getJugador();
        return new ReservaResponse(
                reserva.getId(),
                jugador != null ? jugador.getId() : null,
                jugador != null ? jugador.getNombre() : null,
                reserva.getCancha().getId(),
                reserva.getCancha().getNombre(),
                reserva.getFechaHoraInicio(),
                reserva.getFechaHoraFin(),
                reserva.getEstado().name(),
                reserva.getPrecioTotal(),
                reserva.getSenaPagada(),
                reserva.getNombreClienteManual(),
                reserva.getTelefonoClienteManual(),
                reserva.getDeporteSeleccionado(),
                reserva.getExpiraEn(),
                reserva.getMetodoPago() != null ? reserva.getMetodoPago().name() : null,
                reserva.getTurnoFijo() != null ? reserva.getTurnoFijo().getId() : null
        );
    }
}
