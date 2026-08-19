package com.matiasmeira.sacaladelangulo.cliente.dto;

import java.time.LocalDateTime;

public record ClienteDetalleResponse(
        ClienteResponse cliente,
        String motivoBloqueo,
        LocalDateTime fechaPrimeraReserva
) {
}
