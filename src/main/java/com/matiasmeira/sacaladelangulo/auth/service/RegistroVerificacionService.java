package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.CompletarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.IniciarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRegistroResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
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

    /**
     * Límite de intentos de verificación por código por email, independiente del límite de
     * solicitudes de link (INICIAR_INTENTOS_MAXIMOS): esto protege contra fuerza bruta sobre
     * el código de 6 dígitos de un token ya emitido, no contra el spam de solicitudes.
     */
    private static final int VERIFICAR_CODIGO_INTENTOS_MAXIMOS = 5;
    private static final long VERIFICAR_CODIGO_VENTANA_MILLIS = Duration.ofMinutes(5).toMillis();

    /**
     * Cantidad máxima de intentos fallidos de código permitidos antes de invalidar el token
     * y exigir solicitar uno nuevo (mismo criterio que UsuarioService.MAX_INTENTOS para el
     * OTP de teléfono).
     */
    private static final int CODIGO_INTENTOS_MAXIMOS = 5;

    private final UsuarioRepository usuarioRepository;
    private final TokenVerificacionEmailRepository tokenVerificacionEmailRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

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
        String codigo = String.format("%06d", random.nextInt(1000000));
        TokenVerificacionEmail tokenVerificacion = TokenVerificacionEmail.builder()
                .email(email)
                .token(token)
                .codigo(codigo)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(TOKEN_EXPIRACION_MINUTOS))
                .build();
        tokenVerificacionEmailRepository.save(tokenVerificacion);

        String linkVerificacion = frontendUrl + "/verificar?token=" + token;
        eventPublisher.publishEvent(new VerificacionEmailSolicitadaEvent(email, linkVerificacion, codigo));
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
        TokenVerificacionEmail tokenVerificacion = buscarTokenValidoPorToken(token);
        return new VerificarTokenResponse(tokenVerificacion.getEmail(), true);
    }

    /**
     * Alternativa a {@link #verificarToken(String)}: valida el token a partir del código de
     * 6 dígitos que el usuario tipeó a mano (en vez de hacer click en el link), llevando la
     * cuenta de intentos fallidos para mitigar fuerza bruta sobre el código, igual que
     * UsuarioService.verificarCodigo hace para el OTP de teléfono. Tampoco consume el token:
     * solo completarRegistro lo hace, así ambos caminos (link o código) convergen en el
     * mismo endpoint de completar sin cambios.
     *
     * @param email  Email asociado al token, ya normalizado por el DTO/frontend
     * @param codigo Código de 6 dígitos recibido por email
     * @return VerificarCodigoRegistroResponse con el token para completar el registro
     */
    public VerificarCodigoRegistroResponse verificarCodigo(String email, String codigo) {
        String emailNormalizado = normalizarEmail(email);

        if (!rateLimiterService.tryConsume("verificar-codigo:" + emailNormalizado, VERIFICAR_CODIGO_INTENTOS_MAXIMOS, VERIFICAR_CODIGO_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiadas solicitudes de verificación. Intentá nuevamente en unos minutos.");
        }

        TokenVerificacionEmail tokenVerificacion = buscarTokenValidoPorEmail(emailNormalizado);

        if (tokenVerificacion.getIntentos() >= CODIGO_INTENTOS_MAXIMOS) {
            tokenVerificacionEmailRepository.delete(tokenVerificacion);
            log.warn("Código de verificación de registro bloqueado por exceso de intentos para {}", emailNormalizado);
            throw new RateLimitExceededException("Demasiados intentos, pedí un nuevo código");
        }

        if (!tokenVerificacion.getCodigo().equals(codigo)) {
            tokenVerificacion.setIntentos(tokenVerificacion.getIntentos() + 1);
            tokenVerificacionEmailRepository.save(tokenVerificacion);
            throw new IllegalArgumentException("Código incorrecto");
        }

        return new VerificarCodigoRegistroResponse(tokenVerificacion.getToken());
    }

    /**
     * Paso 3: vuelve a validar el token por seguridad, crea el Usuario (rol PLAYER, email
     * ya verificado), invalida el token utilizado y devuelve el JWT de sesión.
     *
     * @param request DTO con el token y los datos personales/contraseña del jugador
     * @return AuthResponse con el JWT de sesión
     */
    public AuthResponse completarRegistro(CompletarRegistroRequest request) {
        TokenVerificacionEmail tokenVerificacion = buscarTokenValidoPorToken(request.token());
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
        try {
            usuarioRepository.saveAndFlush(usuario);
        } catch (DataIntegrityViolationException ex) {
            // existsByEmail + save no es atómico: un doble submit con el mismo token (el
            // token no se consume hasta el final) puede pasar ambas veces el chequeo antes
            // de que cualquiera inserte (ver M8 en la auditoría).
            tokenVerificacionEmailRepository.delete(tokenVerificacion);
            throw new IllegalArgumentException("El email ya está registrado");
        }

        tokenVerificacionEmailRepository.delete(tokenVerificacion);
        log.info("Registro completado para {}", email);

        eventPublisher.publishEvent(new RegistroCompletadoEvent(email, usuario.getNombre()));

        // Se construye el UserDetails a partir del Usuario recién guardado en vez de
        // volver a consultar la base con loadUserByUsername (ver M4 en la auditoría).
        var userDetails = UsuarioUserDetailsMapper.map(usuario);
        return new AuthResponse(jwtService.generateToken(userDetails));
    }

    private TokenVerificacionEmail buscarTokenValidoPorToken(String token) {
        TokenVerificacionEmail tokenVerificacion = tokenVerificacionEmailRepository.findByToken(token)
                .orElseThrow(() -> new TokenInvalidoException("El token de verificación no es válido"));
        return validarExpiracion(tokenVerificacion);
    }

    private TokenVerificacionEmail buscarTokenValidoPorEmail(String email) {
        TokenVerificacionEmail tokenVerificacion = tokenVerificacionEmailRepository.findByEmail(email)
                .orElseThrow(() -> new TokenInvalidoException("El token de verificación no es válido"));
        return validarExpiracion(tokenVerificacion);
    }

    private TokenVerificacionEmail validarExpiracion(TokenVerificacionEmail tokenVerificacion) {
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
