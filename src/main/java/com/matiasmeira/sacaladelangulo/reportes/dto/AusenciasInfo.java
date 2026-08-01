package com.matiasmeira.sacaladelangulo.reportes.dto;

/**
 * Placeholder honesto: no existe ningún concepto de ausencia/no-show en el modelo actual
 * (ni campo, ni estado de Reserva). En vez de omitir el campo o devolver un 0 engañoso, se
 * expone explícitamente que no está disponible todavía.
 */
public record AusenciasInfo(
        boolean disponible,
        Long total,
        String motivoNoDisponible
) {
}
