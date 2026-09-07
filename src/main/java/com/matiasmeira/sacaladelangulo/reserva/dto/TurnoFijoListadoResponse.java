package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Fila del listado de turnos fijos del establecimiento: la regla sin sus ocurrencias (eso
 * sería N+1, ver TurnoFijoResponse) más los dos agregados resueltos en una sola consulta
 * para toda la página (ver ReservaRepository.agregadosPorTurnoFijo).
 */
public record TurnoFijoListadoResponse(
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
        long ocurrenciasActivas,
        LocalDateTime proximaOcurrencia
) {
}
