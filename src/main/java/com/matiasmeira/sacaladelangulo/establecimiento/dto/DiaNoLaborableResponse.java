package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import java.time.LocalDate;

public record DiaNoLaborableResponse(
        Long id,
        LocalDate fecha,
        String motivo
) {
}
