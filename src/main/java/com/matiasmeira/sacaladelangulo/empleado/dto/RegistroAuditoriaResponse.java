package com.matiasmeira.sacaladelangulo.empleado.dto;

import java.time.LocalDateTime;

public record RegistroAuditoriaResponse(
        Long id,
        Long empleadoId,
        String empleadoNombre,
        Long actorId,
        String accion,
        Long entidadAfectadaId,
        Boolean exitoso,
        String detalle,
        LocalDateTime fechaHora
) {
}
