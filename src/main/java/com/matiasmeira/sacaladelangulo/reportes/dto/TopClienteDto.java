package com.matiasmeira.sacaladelangulo.reportes.dto;

public record TopClienteDto(
        Long jugadorId,
        String nombre,
        long cantidadReservas
) {
}
