package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UsuarioUserDetailsMapper - isEnabled")
class UsuarioUserDetailsMapperTest {

    @Test
    @DisplayName("map_IsActiveTrueSinDeletedAt_QuedaHabilitado")
    void map_IsActiveTrueSinDeletedAt_QuedaHabilitado() {
        Usuario usuario = Usuario.builder()
                .email("activo@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(true)
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertTrue(principal.isEnabled());
    }

    @Test
    @DisplayName("map_IsActiveFalse_QuedaDeshabilitado")
    void map_IsActiveFalse_QuedaDeshabilitado() {
        Usuario usuario = Usuario.builder()
                .email("inactivo@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(false)
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertFalse(principal.isEnabled());
    }

    @Test
    @DisplayName("map_IsActiveTrueConDeletedAt_QuedaDeshabilitado")
    void map_IsActiveTrueConDeletedAt_QuedaDeshabilitado() {
        // Estado que no debería darse en producción (isActive se pone en false al anonimizar),
        // pero es justamente el caso que el chequeo explícito de deletedAt cubre: si algún
        // flujo futuro reactivara isActive sin tocar deletedAt, el login sigue bloqueado.
        Usuario usuario = Usuario.builder()
                .email("reactivado@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(true)
                .deletedAt(LocalDateTime.now())
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertFalse(principal.isEnabled());
    }
}
