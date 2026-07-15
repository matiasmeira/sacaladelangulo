package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.CompletarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.IniciarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarTokenResponse;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TokenVerificacionEmail;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.TokenVerificacionEmailRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.TokenExpiradoException;
import com.matiasmeira.sacaladelangulo.core.exception.TokenInvalidoException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Servicio de negocio para el registro de jugadores en 2 pasos: primero se verifica el
 * email mediante un link (token opaco con expiración), y recién al confirmarlo se piden
 * el resto de los datos personales y la contraseña. Separado de AuthService igual que
 * UsuarioService lo está para la verificación de teléfono por OTP: es un flujo propio,
 * con su propia entidad y ciclo de vida, ajeno a la responsabilidad de "autenticar/
 * registrar en un solo paso" que ya tiene AuthService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RegistroVerificacionService {

    /**
     * Límite de solicitudes de link de verificación por email, para no permitir spamear
     * la casilla de un tercero. La ventana coincide con la expiración del token: alcanza
     * con volver a pedirlo una vez vencido.
     */
    private static final int INICIAR_INTENTOS_MAXIMOS = 3;
    private static final long INICIAR_VENTANA_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long TOKEN_EXPIRACION_MINUTOS = 15;

    private final UsuarioRepository usuarioRepository;
    private final TokenVerificacionEmailRepository tokenVerificacionEmailRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RateLimiterService rateLimiterService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /**
     * Paso 1: recibe solo el email, genera un token de verificación y publica el evento
     * que dispara el link (ver RegistroVerificacionEmailListener) recién después de que
     * esta transacción haga commit — así una falla del envío de email no revierte el
     * token ya persistido, y no se mantiene la conexión de base de datos reservada
     * durante la latencia de esa llamada externa.
     *
     * @param request DTO con el email a verificar
     */
    public void iniciarRegistro(IniciarRegistroRequest request) {
        String email = normalizarEmail(request.email());

        if (!rateLimiterService.tryConsume("registro-email:" + email, INICIAR_INTENTOS_MAXIMOS, INICIAR_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiadas solicitudes de registro. Intentá nuevamente en unos minutos.");
        }

        if (usuarioRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("El email ya está registrado");
        }

        tokenVerificacionEmailRepository.deleteByEmail(email);

        String token = UUID.randomUUID().toString();
        TokenVerificacionEmail tokenVerificacion = TokenVerificacionEmail.builder()
                .email(email)
                .token(token)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(TOKEN_EXPIRACION_MINUTOS))
                .build();
        tokenVerificacionEmailRepository.save(tokenVerificacion);

        String linkVerificacion = frontendUrl + "/verificar?token=" + token;
        eventPublisher.publishEvent(new VerificacionEmailSolicitadaEvent(email, linkVerificacion));
        log.info("Token de verificación de registro generado para {}", email);
    }

    /**
     * Paso 2: valida el token recibido en el link (existe y no expiró) sin consumirlo,
     * para que el frontend pueda avanzar a la pantalla de completar los datos.
     *
     * @param token Token recibido como query param
     * @return VerificarTokenResponse con el email asociado al token
     */
    @Transactional(readOnly = true)
    public VerificarTokenResponse verificarToken(String token) {
        TokenVerificacionEmail tokenVerificacion = buscarTokenValido(token);
        return new VerificarTokenResponse(tokenVerificacion.getEmail(), true);
    }

    /**
     * Paso 3: vuelve a validar el token por seguridad, crea el Usuario (rol PLAYER, email
     * ya verificado), invalida el token utilizado y devuelve el JWT de sesión.
     *
     * @param request DTO con el token y los datos personales/contraseña del jugador
     * @return AuthResponse con el JWT de sesión
     */
    public AuthResponse completarRegistro(CompletarRegistroRequest request) {
        TokenVerificacionEmail tokenVerificacion = buscarTokenValido(request.token());
        String email = tokenVerificacion.getEmail();

        if (usuarioRepository.existsByEmail(email)) {
            tokenVerificacionEmailRepository.delete(tokenVerificacion);
            throw new IllegalArgumentException("El email ya está registrado");
        }

        Usuario usuario = Usuario.builder()
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .nombre(request.nombre())
                .telefono(request.telefono())
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build();
        usuarioRepository.save(usuario);

        tokenVerificacionEmailRepository.delete(tokenVerificacion);
        log.info("Registro completado para {}", email);

        var userDetails = userDetailsService.loadUserByUsername(usuario.getEmail());
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    private TokenVerificacionEmail buscarTokenValido(String token) {
        TokenVerificacionEmail tokenVerificacion = tokenVerificacionEmailRepository.findByToken(token)
                .orElseThrow(() -> new TokenInvalidoException("El token de verificación no es válido"));

        if (LocalDateTime.now().isAfter(tokenVerificacion.getFechaExpiracion())) {
            tokenVerificacionEmailRepository.delete(tokenVerificacion);
            throw new TokenExpiradoException("El token de verificación expiró, solicitá uno nuevo");
        }

        return tokenVerificacion;
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
