package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;

/**
 * Construye el UserDetails de Spring Security a partir de un Usuario ya cargado en
 * memoria, para no volver a golpear la base con UserDetailsService.loadUserByUsername
 * cuando ya tenemos el Usuario a mano (justo después de authenticationManager.authenticate(),
 * que ya lo cargó internamente vía UserDetailsService, o recién creado/guardado en un
 * registro). Es la misma lógica de mapeo que usa el bean UserDetailsService en
 * SecurityConfig, extraída acá para no duplicarla (ver M4 en la auditoría).
 */
public final class UsuarioUserDetailsMapper {

    private UsuarioUserDetailsMapper() {
    }

    public static UserDetails map(Usuario usuario) {
        return new UsuarioPrincipal(
                usuario.getEmail(),
                usuario.getPassword(),
                Boolean.TRUE.equals(usuario.getIsActive()),
                List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getRol().name())),
                usuario.getTokenVersion() == null ? 0 : usuario.getTokenVersion()
        );
    }
}
