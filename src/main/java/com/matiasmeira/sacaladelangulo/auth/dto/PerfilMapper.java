package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PerfilMapper {

    /**
     * Set.copyOf materializa la colección lazy `permisos` mientras la sesión de
     * Hibernate del caller (transaccional) sigue abierta, en vez de reenviar el
     * PersistentSet vivo: con spring.jpa.open-in-view=false esa sesión se cierra antes
     * de que Jackson serialice la respuesta en la capa web, y un Set lazy sin
     * inicializar revienta con LazyInitializationException.
     */
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
                esEmpleado ? Set.copyOf(usuario.getPermisos()) : Set.of()
        );
    }
}
