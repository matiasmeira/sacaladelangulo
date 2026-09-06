package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO para respuesta de reserva.
 */
public record ReservaResponse(
        Long id,
        Long jugadorId,
        String jugadorNombre,
        Long canchaId,
        String canchaNombre,
        LocalDateTime fechaHoraInicio,
        LocalDateTime fechaHoraFin,
        String estado,
        BigDecimal precioTotal,
        BigDecimal senaPagada,
        String nombreClienteManual,
        String telefonoClienteManual,
        Deporte deporteSeleccionado,
        LocalDateTime expiraEn,
        String metodoPago,
        /** Id de la serie si esta reserva es una ocurrencia de un turno fijo; null si no. */
        Long turnoFijoId
) {
}
