package com.matiasmeira.sacaladelangulo.reserva.dto;

import java.time.LocalDate;

/** `desde` opcional: si no viene, la serie se corta desde ahora. */
public record CancelarTurnoFijoRequest(LocalDate desde) {
}
