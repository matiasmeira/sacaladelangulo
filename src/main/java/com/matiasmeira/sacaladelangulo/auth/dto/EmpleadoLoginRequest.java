package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO para el login de mostrador: el empleado toca su nombre (dentro del
 * establecimiento activo) e ingresa su PIN de 4 dígitos. El establecimiento efectivo
 * se toma de la cookie de dispositivo de caja (ver DispositivoCajaGate), no de este
 * campo: `establecimientoId` queda opcional y solo se usa para detectar un mismatch
 * (ver AuthService.authenticateEmpleado).
 */
public record EmpleadoLoginRequest(
        Long establecimientoId,

        @NotBlank(message = "El nombre es obligatorio")
        String nombre,

        @NotBlank(message = "El PIN es obligatorio")
        @Pattern(regexp = "\\d{4}", message = "El PIN debe tener exactamente 4 dígitos")
        String pin
) {
}
