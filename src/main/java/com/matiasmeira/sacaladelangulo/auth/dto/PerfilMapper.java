package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PerfilMapper {

    public PerfilResponse mapToResponse(Usuario usuario) {
        boolean esEmpleado = usuario.getRol() == Role.EMPLOYEE;
        return new PerfilResponse(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.getRol(),
                usuario.getPlanSuscripcion(),
                usuario.getEmailVerified(),
                usuario.getTelefonoVerificado(),
                esEmpleado ? usuario.getEstablecimiento().getId() : null,
                esEmpleado ? usuario.getPermisos() : Set.of()
        );
    }
}
