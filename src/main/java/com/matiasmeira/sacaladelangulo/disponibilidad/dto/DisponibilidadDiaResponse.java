package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Disponibilidad de todas las canchas activas del establecimiento para un día puntual.
 * Cuando el establecimiento no abre ese día (día no laborable, o sin HorarioAtencion
 * cargado para ese día de la semana), "abierto" es false, "motivoCierre" explica por qué
 * y "canchas" viene vacío.
 */
public record DisponibilidadDiaResponse(
        LocalDate fecha,
        Boolean abierto,
        String motivoCierre,
        List<DisponibilidadCanchaResponse> canchas
) {
}
