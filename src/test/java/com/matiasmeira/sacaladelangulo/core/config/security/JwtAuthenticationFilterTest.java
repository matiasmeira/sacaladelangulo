package com.matiasmeira.sacaladelangulo.core.config.security;

import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter - Seguridad JWT")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter = new JwtAuthenticationFilter(jwtService, userDetailsService);
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("doFilterInternal_SinAuthorizationHeader_NoAutentica")
    void doFilterInternal_SinAuthorizationHeader_NoAutentica() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter_TokenInvalido_DejaPasarPeroNoAutentica")
    void doFilter_TokenInvalido_DejaPasarPeroNoAutentica() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(jwtService.extractUsername("invalid-token")).thenThrow(new io.jsonwebtoken.JwtException("Token inválido"));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // El filtro no debe fijar el status manualmente: es Spring Security quien decide
        // el 401/403 según si la ruta requiere autenticación, evitando que ese status
        // quede sobreescrito por la respuesta real del controlador.
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain).doFilter(request, response);
        org.junit.jupiter.api.Assertions.assertNull(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("doFilter_BearerVacio_DejaPasarPeroNoAutentica")
    void doFilter_BearerVacio_DejaPasarPeroNoAutentica() throws Exception {
        // "Bearer " sin nada después: jjwt rechaza el string vacío con IllegalArgumentException,
        // no con JwtException (ver M1 en la auditoría).
        when(request.getHeader("Authorization")).thenReturn("Bearer ");
        when(jwtService.extractUsername("")).thenThrow(new IllegalArgumentException("JWT String argument cannot be null or empty"));

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(filterChain).doFilter(request, response);
        org.junit.jupiter.api.Assertions.assertNull(
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("doFilter_TokenValidoUsuarioInhabilitado_NoAutentica")
    void doFilter_TokenValidoUsuarioInhabilitado_NoAutentica() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.extractUsername("valid-token")).thenReturn("jugador@test.com");

        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("jugador@test.com")
                .password("password")
                .disabled(true)
                .authorities("ROLE_PLAYER")
                .build();

        when(userDetailsService.loadUserByUsername("jugador@test.com")).thenReturn(userDetails);
        when(jwtService.isTokenValid("valid-token", userDetails)).thenReturn(true);

        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
