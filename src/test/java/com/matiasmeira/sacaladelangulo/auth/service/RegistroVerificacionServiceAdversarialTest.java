package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.VerificarCodigoRegistroResponse;
import com.matiasmeira.sacaladelangulo.auth.model.TokenVerificacionEmail;
import com.matiasmeira.sacaladelangulo.auth.repository.TokenVerificacionEmailRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.core.security.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Complementa RegistroVerificacionServiceTest con el borde exacto que faltaba: el código
 * CORRECTO en el último intento permitido (intentos ya en CODIGO_INTENTOS_MAXIMOS - 1) debe
 * seguir aceptándose — el bloqueo por exceso de intentos se dispara recién en el intento
 * SIGUIENTE a este, no en este.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RegistroVerificacionService - Borde exacto de intentos de código")
class RegistroVerificacionServiceAdversarialTest {

    @Mock private UsuarioRepository usuarioRepository;
    @Mock private TokenVerificacionEmailRepository tokenVerificacionEmailRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RateLimiterService rateLimiterService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RegistroVerificacionService registroVerificacionService;

    @BeforeEach
    void setUp() {
        lenient().when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(registroVerificacionService, "frontendUrl", "http://localhost:5173");
    }

    @Test
    @DisplayName("verificarCodigo: código correcto en el último intento disponible (4to de 5) todavía se acepta")
    void verificarCodigo_CorrectoEnElUltimoIntentoDisponible_Acepta() {
        String email = "jugador@test.com";
        String codigo = "654321";
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email(email)
                .tokenHash(TokenHasher.sha256Hex("token-original"))
                .codigoHash(TokenHasher.sha256Hex(codigo))
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .intentos(4) // CODIGO_INTENTOS_MAXIMOS = 5: el chequeo es ">= 5", así que 4 todavía pasa
                .build();

        when(tokenVerificacionEmailRepository.findByEmail(email)).thenReturn(Optional.of(token));
        when(tokenVerificacionEmailRepository.save(org.mockito.ArgumentMatchers.any(TokenVerificacionEmail.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        VerificarCodigoRegistroResponse response = assertDoesNotThrow(
                () -> registroVerificacionService.verificarCodigo(email, codigo));

        org.junit.jupiter.api.Assertions.assertNotNull(response.token());
    }
}
