package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * La regla de un turno fijo. `ocurrencias` viene poblada en el alta y en el detalle, y
 * vacía en el listado: ahí traerlas sería N+1 y el listado no las muestra.
 */
public record TurnoFijoResponse(
        Long id,
        Long canchaId,
        String canchaNombre,
        Deporte deporteSeleccionado,
        DayOfWeek diaSemana,
        LocalTime horaInicio,
        LocalTime horaFin,
        LocalDate fechaInicioPeriodo,
        LocalDate fechaFinPeriodo,
        String estado,
        LocalDate canceladoDesde,
        Long jugadorId,
        String jugadorNombre,
        String nombreClienteManual,
        String telefonoClienteManual,
        Long renovadoDesdeId,
        List<ReservaResponse> ocurrencias
) {
}
