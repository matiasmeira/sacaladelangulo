package com.matiasmeira.sacaladelangulo.auth.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * UserDetails propio (en vez del User genérico de Spring Security) para poder llevar
 * tokenVersion hasta JwtService sin volver a consultar la base: tanto el filtro JWT
 * como AuthService ya obtienen este principal a partir de un Usuario recién leído de
 * la base (ver B3 en la auditoría).
 */
public class UsuarioPrincipal implements UserDetails {

    private final String username;
    private final String password;
    private final boolean enabled;
    private final Collection<? extends GrantedAuthority> authorities;
    private final int tokenVersion;

    public UsuarioPrincipal(String username, String password, boolean enabled,
                             Collection<? extends GrantedAuthority> authorities, int tokenVersion) {
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.authorities = authorities;
        this.tokenVersion = tokenVersion;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
