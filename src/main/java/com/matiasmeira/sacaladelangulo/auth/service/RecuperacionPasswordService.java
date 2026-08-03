package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.ResetPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.SolicitarRecuperacionPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.model.TokenRecuperacionPassword;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.TokenRecuperacionPasswordRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.exception.TokenExpiradoException;
import com.matiasmeira.sacaladelangulo.core.exception.TokenInvalidoException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Servicio de negocio para la recuperación de contraseña en 2 pasos: primero se solicita
 * un link/código a un email (sin revelar si esa cuenta existe), y recién con el token o el
 * código válido se puede fijar una nueva contraseña. Mismo diseño que RegistroVerificacionService
 * (token opaco + código de 6 dígitos + intentos + expiración), del que este servicio es
 * hermano dentro de auth/: no lo reemplaza ni lo extiende, solo comparte convenciones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RecuperacionPasswordService {

    /**
     * Límite de solicitudes de recuperación por email, mismo criterio que
     * RegistroVerificacionService.INICIAR_INTENTOS_MAXIMOS: la ventana coincide con la
     * expiración del token, alcanza con volver a pedirlo una vez vencido.
     */
    private static final int SOLICITAR_INTENTOS_MAXIMOS = 3;
    private static final long SOLICITAR_VENTANA_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long TOKEN_EXPIRACION_MINUTOS = 15;

    /**
     * Cantidad máxima de intentos fallidos de código permitidos antes de invalidar el token
     * y exigir solicitar uno nuevo (mismo criterio que RegistroVerificacionService.CODIGO_INTENTOS_MAXIMOS).
     */
    private static final int CODIGO_INTENTOS_MAXIMOS = 5;

    private final UsuarioRepository usuarioRepository;
    private final TokenRecuperacionPasswordRepository tokenRecuperacionPasswordRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiterService rateLimiterService;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();

    @Value("${app.frontend-url}")
    private String frontendUrl;

    /**
     * Paso 1: recibe un email y, si corresponde a una cuenta existente, genera un token de
     * recuperación y publica el evento que dispara el link/código (ver
     * RecuperacionPasswordEmailListener) recién después de que esta transacción haga commit,
     * mismo motivo que iniciarRegistro. Si el email no está registrado, el método retorna
     * normalmente sin crear nada ni publicar nada: el controller responde 200 en ambos casos
     * para no convertir este endpoint en un oráculo de qué emails existen (a diferencia del
     * registro, acá SÍ importa no filtrar esa información porque es la cuenta de otra
     * persona la que está en juego, no la propia).
     *
     * @param request DTO con el email a recuperar
     */
    public void solicitarRecuperacion(SolicitarRecuperacionPasswordRequest request) {
        String email = normalizarEmail(request.email());

        if (!rateLimiterService.tryConsume("recuperar-password:" + email, SOLICITAR_INTENTOS_MAXIMOS, SOLICITAR_VENTANA_MILLIS)) {
            throw new RateLimitExceededException("Demasiadas solicitudes de recuperación. Intentá nuevamente en unos minutos.");
        }

        if (usuarioRepository.findByEmail(email).isEmpty()) {
            log.info("Solicitud de recuperación de contraseña para email no registrado");
            return;
        }

        tokenRecuperacionPasswordRepository.deleteByEmail(email);

        String token = UUID.randomUUID().toString();
        String codigo = String.format("%06d", random.nextInt(1000000));
        TokenRecuperacionPassword tokenRecuperacion = TokenRecuperacionPassword.builder()
                .email(email)
                .token(token)
                .codigo(codigo)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(TOKEN_EXPIRACION_MINUTOS))
                .build();
        tokenRecuperacionPasswordRepository.save(tokenRecuperacion);

        String linkRecuperacion = frontendUrl + "/restablecer?token=" + token;
        eventPublisher.publishEvent(new RecuperacionPasswordSolicitadaEvent(email, linkRecuperacion, codigo));
        log.info("Token de recuperación de contraseña generado para {}", email);
    }

    /**
     * Paso 2: resuelve el token válido (por link o por código, ver resolverToken), fija la
     * nueva contraseña e invalida cualquier JWT ya emitido (mismo patrón que
     * AuthService.logout), para que un token robado antes del reset deje de servir.
     *
     * @param request DTO con el token o el email+código, y la nueva contraseña
     */
    public void resetPassword(ResetPasswordRequest request) {
        TokenRecuperacionPassword tokenRecuperacion = resolverToken(request);
        String email = tokenRecuperacion.getEmail();

        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        usuario.setPassword(passwordEncoder.encode(request.nuevaPassword()));
        usuario.setTokenVersion(usuario.getTokenVersion() + 1);
        usuarioRepository.save(usuario);

        tokenRecuperacionPasswordRepository.delete(tokenRecuperacion);
        log.info("Contraseña restablecida para {}", email);

        eventPublisher.publishEvent(new PasswordCambiadaEvent(email));
    }

    /**
     * Decide cuál de los dos caminos mutuamente excluyentes usar para resolver el token: el
     * token del link, o el email+código tipeado a mano. Exactamente uno debe estar presente;
     * no es una regla expresable con bean validation porque cruza campos, así que se valida
     * acá en vez de en el DTO.
     */
    private TokenRecuperacionPassword resolverToken(ResetPasswordRequest request) {
        boolean tieneToken = StringUtils.hasText(request.token());
        boolean tieneEmailYCodigo = StringUtils.hasText(request.email()) && StringUtils.hasText(request.codigo());

        if (tieneToken && tieneEmailYCodigo) {
            throw new IllegalArgumentException("Enviá el token o el email+código, no ambos");
        }
        if (!tieneToken && !tieneEmailYCodigo) {
            throw new IllegalArgumentException("Enviá el token o el email+código");
        }

        if (tieneToken) {
            return buscarTokenValidoPorToken(request.token());
        }
        return validarCodigo(normalizarEmail(request.email()), request.codigo());
    }

    private TokenRecuperacionPassword validarCodigo(String email, String codigo) {
        TokenRecuperacionPassword tokenRecuperacion = buscarTokenValidoPorEmail(email);

        if (tokenRecuperacion.getIntentos() >= CODIGO_INTENTOS_MAXIMOS) {
            tokenRecuperacionPasswordRepository.delete(tokenRecuperacion);
            log.warn("Código de recuperación de contraseña bloqueado por exceso de intentos para {}", email);
            throw new RateLimitExceededException("Demasiados intentos, pedí un nuevo código");
        }

        if (!tokenRecuperacion.getCodigo().equals(codigo)) {
            tokenRecuperacion.setIntentos(tokenRecuperacion.getIntentos() + 1);
            tokenRecuperacionPasswordRepository.save(tokenRecuperacion);
            throw new IllegalArgumentException("Código incorrecto");
        }

        return tokenRecuperacion;
    }

    private TokenRecuperacionPassword buscarTokenValidoPorToken(String token) {
        TokenRecuperacionPassword tokenRecuperacion = tokenRecuperacionPasswordRepository.findByToken(token)
                .orElseThrow(() -> new TokenInvalidoException("El token de recuperación no es válido"));
        return validarExpiracion(tokenRecuperacion);
    }

    private TokenRecuperacionPassword buscarTokenValidoPorEmail(String email) {
        TokenRecuperacionPassword tokenRecuperacion = tokenRecuperacionPasswordRepository.findByEmail(email)
                .orElseThrow(() -> new TokenInvalidoException("El token de recuperación no es válido"));
        return validarExpiracion(tokenRecuperacion);
    }

    private TokenRecuperacionPassword validarExpiracion(TokenRecuperacionPassword tokenRecuperacion) {
        if (LocalDateTime.now().isAfter(tokenRecuperacion.getFechaExpiracion())) {
            tokenRecuperacionPasswordRepository.delete(tokenRecuperacion);
            throw new TokenExpiradoException("El token de recuperación expiró, solicitá uno nuevo");
        }

        return tokenRecuperacion;
    }

    private String normalizarEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
