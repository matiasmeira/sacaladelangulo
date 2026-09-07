package com.matiasmeira.sacaladelangulo.reserva.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resultado de cancelar una serie. `omitidas` no es decorativo: una ocurrencia FINALIZADA o
 * AUSENTE no se cancela nunca, y si el dueño no lo ve, cree que la serie está muerta
 * mientras sigue apareciendo en los reportes.
 */
public record CancelacionTurnoFijoResponse(
        int canceladas,
        List<OcurrenciaOmitida> omitidas
) {
    public record OcurrenciaOmitida(LocalDateTime fecha, String motivo) {
    }
}
