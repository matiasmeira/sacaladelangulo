package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

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
        return User.builder()
                .username(usuario.getEmail())
                .password(usuario.getPassword())
                .disabled(!Boolean.TRUE.equals(usuario.getIsActive()))
                .roles(usuario.getRol().name())
                .build();
    }
}
