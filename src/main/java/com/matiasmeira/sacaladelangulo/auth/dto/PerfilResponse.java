package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;

import java.util.Set;

/**
 * Perfil del usuario autenticado (GET /api/v1/usuarios/me): datos base más los flags
 * de verificación que el front usa para gatear el onboarding. establecimientoId y
 * permisos solo se completan para rol EMPLOYEE; para el resto van null/vacío.
 */
public record PerfilResponse(
        Long id,
        String email,
        String nombre,
        Role rol,
        PlanSuscripcion planSuscripcion,
        Boolean emailVerified,
        Boolean telefonoVerificado,
        Long establecimientoId,
        Set<PermisoEmpleado> permisos
) {
}
