package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.RegisterRequest;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Servicio de negocio para registro y autenticación de usuarios.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /**
     * Límites por identidad de negocio (no por IP: ver RateLimitFilter para eso),
     * para que no alcance con rotar de IP para seguir probando contraseñas/PIN
     * contra una cuenta puntual.
     */
    private static final int LOGIN_INTENTOS_MAXIMOS = 8;
    private static final long LOGIN_VENTANA_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int LOGIN_EMPLEADO_INTENTOS_MAXIMOS = 5;
    private static final long LOGIN_EMPLEADO_VENTANA_MILLIS = Duration.ofMinutes(5).toMillis();

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final RateLimiterService rateLimiterService;

    @Value("${jwt.empleado-expiration-millis:3600000}")
    private long empleadoExpirationMillis;

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
        if (!rateLimiterService.tryConsume("login:" + email, LOGIN_INTENTOS_MAXIMOS, LOGIN_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiados intentos de inicio de sesión. Intente nuevamente en unos minutos.");
        }

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

    /**
     * Login de mostrador: el empleado se identifica por nombre dentro de un
     * establecimiento (no tiene email propio, ver EmpleadoService) + su PIN de 4
     * dígitos. Reutiliza el mismo AuthenticationManager/BCrypt que el login normal,
     * pero emite un token de vida corta (independiente de la sesión del dueño) con
     * el ID del empleado como claim para que el frontend lo muestre sin otra llamada.
     */
    public AuthResponse authenticateEmpleado(EmpleadoLoginRequest request) {
        String claveLimite = "login-empleado:" + request.establecimientoId() + ":" + request.nombre();
        if (!rateLimiterService.tryConsume(claveLimite, LOGIN_EMPLEADO_INTENTOS_MAXIMOS, LOGIN_EMPLEADO_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiados intentos de inicio de sesión. Intente nuevamente en unos minutos.");
        }

        Usuario empleado = usuarioRepository.findByEstablecimientoIdAndNombreAndRol(
                        request.establecimientoId(), request.nombre(), Role.EMPLOYEE)
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        if (!Boolean.TRUE.equals(empleado.getIsActive())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(empleado.getEmail(), request.pin())
            );
        } catch (BadCredentialsException ex) {
            throw new BadCredentialsException("Credenciales inválidas");
        } catch (AuthenticationException ex) {
            throw new IllegalArgumentException("Error de autenticación");
        }

        var userDetails = userDetailsService.loadUserByUsername(empleado.getEmail());
        String token = jwtService.generateToken(userDetails, Map.of("empleadoId", empleado.getId()), empleadoExpirationMillis);
        return new AuthResponse(token);
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
