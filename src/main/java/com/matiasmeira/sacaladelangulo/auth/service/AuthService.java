package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.EmpleadoLoginRequest;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
    private final RateLimiterService rateLimiterService;

    @Value("${jwt.empleado-expiration-millis:900000}")
    private long empleadoExpirationMillis;

    public AuthResponse authenticate(AuthRequest request) {
        String email = normalizarEmail(request.email());
        if (!rateLimiterService.tryConsume("login:" + email, LOGIN_INTENTOS_MAXIMOS, LOGIN_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiados intentos de inicio de sesión. Intente nuevamente en unos minutos.");
        }

        Authentication resultado;
        try {
            resultado = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password())
            );
        } catch (AuthenticationException ex) {
            // Un único mensaje/status para cualquier fallo de autenticación (contraseña
            // incorrecta, usuario inexistente, o cualquier otra AuthenticationException que
            // Spring Security pueda lanzar en el futuro): distinguir entre subtipos convertiría
            // este endpoint en un oráculo del estado de la cuenta.
            throw new BadCredentialsException("Credenciales inválidas");
        }

        // authenticationManager.authenticate() ya cargó el UserDetails internamente (vía
        // DaoAuthenticationProvider -> UserDetailsService) y lo devuelve como principal: se
        // reutiliza en vez de volver a consultar la base con loadUserByUsername.
        var userDetails = (UserDetails) resultado.getPrincipal();
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    /**
     * Login de mostrador: el empleado se identifica por nombre dentro de un
     * establecimiento (no tiene email propio, ver EmpleadoService) + su PIN de 4
     * dígitos. Reutiliza el mismo AuthenticationManager/BCrypt que el login normal,
     * pero emite un token de vida corta (independiente de la sesión del dueño) con
     * el ID del empleado como claim para que el frontend lo muestre sin otra llamada.
     *
     * @param establecimientoIdDispositivo establecimiento derivado de la cookie de
     *                                      dispositivo de caja (fuente de verdad, ver
     *                                      DispositivoCajaGate): si el body trae su
     *                                      propio establecimientoId y no coincide, se
     *                                      rechaza con el mismo error genérico que
     *                                      cualquier otro fallo de login.
     */
    public AuthResponse authenticateEmpleado(EmpleadoLoginRequest request, Long establecimientoIdDispositivo) {
        if (request.establecimientoId() != null && !request.establecimientoId().equals(establecimientoIdDispositivo)) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        // Normalizado antes de armar la clave de rate limit y de consultar la base, para
        // que variar mayúsculas/espacios no sirva ni para eludir el límite de intentos
        // ni para esquivar la búsqueda por nombre.
        String nombre = normalizarNombre(request.nombre());
        String claveLimite = "login-empleado:" + establecimientoIdDispositivo + ":" + nombre;
        if (!rateLimiterService.tryConsume(claveLimite, LOGIN_EMPLEADO_INTENTOS_MAXIMOS, LOGIN_EMPLEADO_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiados intentos de inicio de sesión. Intente nuevamente en unos minutos.");
        }

        // AndIsActiveTrue en la consulta y no como chequeo posterior: el alta sólo garantiza
        // unicidad de nombre entre los ACTIVOS, así que un empleado dado de baja y otro
        // vigente pueden compartir nombre. Un finder sin ese filtro devuelve 2 filas para un
        // Optional y revienta el login (ver AuthServiceEmpleadoHomonimoTest).
        Usuario empleado = usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(
                        establecimientoIdDispositivo, nombre, Role.EMPLOYEE)
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));

        Authentication resultado;
        try {
            resultado = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(empleado.getEmail(), request.pin())
            );
        } catch (AuthenticationException ex) {
            // Mismo criterio que authenticate(): un único mensaje/status para cualquier
            // fallo de autenticación.
            throw new BadCredentialsException("Credenciales inválidas");
        }

        var userDetails = (UserDetails) resultado.getPrincipal();
        String token = jwtService.generateToken(userDetails, Map.of("empleadoId", empleado.getId()), empleadoExpirationMillis);
        return new AuthResponse(token);
    }

    /**
     * Invalida cualquier JWT ya emitido para este usuario: incrementa tokenVersion, que
     * JwtService compara contra el claim del token en cada petición. No requiere borrar nada del lado del cliente.
     */
    public void logout(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        usuario.setTokenVersion(usuario.getTokenVersion() + 1);
        usuarioRepository.save(usuario);
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String normalizarNombre(String nombre) {
        return nombre == null ? null : nombre.trim().toLowerCase();
    }
}
