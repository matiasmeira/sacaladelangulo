package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Servicio de negocio para registro y autenticación de usuarios.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    public AuthResponse register(RegisterRequest request) {
        if (usuarioRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        Usuario usuario = Usuario.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nombre(request.nombre())
                .telefono(request.telefono())
                .rol(request.role())
                .isActive(true)
                .emailVerified(false)
                .build();

        usuarioRepository.save(usuario);
        var userDetails = userDetailsService.loadUserByUsername(usuario.getEmail());
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    public AuthResponse authenticate(AuthRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );
        } catch (AuthenticationException ex) {
            throw new IllegalArgumentException("Credenciales inválidas");
        }

        var userDetails = userDetailsService.loadUserByUsername(request.email());
        return new AuthResponse(jwtService.generateToken(userDetails));
    }
}
