package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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

    public AuthResponse registerPlayer(RegisterRequest request) {
        String email = normalizarEmail(request.email());
        if (usuarioRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        Usuario usuario = Usuario.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .nombre(request.nombre())
                .telefono(null)
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build();

        usuarioRepository.save(usuario);
        var userDetails = userDetailsService.loadUserByUsername(usuario.getEmail());
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    public AuthResponse registerOwner(RegisterRequest request) {
        String email = normalizarEmail(request.email());
        if (usuarioRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        Usuario usuario = Usuario.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .nombre(request.nombre())
                .telefono(null)
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .fechaFinPrueba(LocalDateTime.now().plusMonths(1))
                .build();

        usuarioRepository.save(usuario);
        var userDetails = userDetailsService.loadUserByUsername(usuario.getEmail());
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    public AuthResponse authenticate(AuthRequest request) {
        String email = normalizarEmail(request.email());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password())
            );
        } catch (BadCredentialsException ex) {
            throw new BadCredentialsException("Credenciales inválidas");
        } catch (AuthenticationException ex) {
            throw new IllegalArgumentException("Error de autenticación");
        }

        var userDetails = userDetailsService.loadUserByUsername(email);
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
