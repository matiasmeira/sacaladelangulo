package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * DTO para actualizar la política de cancelación (semántica PATCH): un campo en null
 * significa "no modificar", por eso ninguno de los dos lleva @NotNull. Bean Validation no
 * evalúa @Min/@Max sobre un valor null, así que dejarlo en null pasa la validación sin
 * problema.
 */
public record ActualizarPoliticaCancelacionRequest(
        @Min(value = 0, message = "Las horas de cancelación no pueden ser negativas")
        @Max(value = 168, message = "Las horas de cancelación no pueden superar las 168 (una semana)")
        Integer horasCancelacionAntesPartido,

        @Min(value = 0, message = "Los minutos de gracia no pueden ser negativos")
        @Max(value = 1440, message = "Los minutos de gracia no pueden superar los 1440 (un día)")
        Integer minutosGraciaCancelacion
) {
}
