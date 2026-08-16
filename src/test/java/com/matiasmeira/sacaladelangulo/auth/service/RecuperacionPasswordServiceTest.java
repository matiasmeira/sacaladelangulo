package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.ResetPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.SolicitarRecuperacionPasswordRequest;
import com.matiasmeira.sacaladelangulo.auth.model.TokenRecuperacionPassword;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.TokenRecuperacionPasswordRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.TokenExpiradoException;
import com.matiasmeira.sacaladelangulo.core.exception.TokenInvalidoException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.core.security.TokenHasher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecuperacionPasswordService - Recuperación de contraseña en 2 pasos")
class RecuperacionPasswordServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TokenRecuperacionPasswordRepository tokenRecuperacionPasswordRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RecuperacionPasswordService recuperacionPasswordService;

    @BeforeEach
    void setUp() {
        lenient().when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(recuperacionPasswordService, "frontendUrl", "http://localhost:5173");
    }

    @Test
    @DisplayName("solicitarRecuperacion_Exito_GeneraTokenYPublicaEventoDeEmail")
    void solicitarRecuperacion_Exito_GeneraTokenYPublicaEventoDeEmail() {
        SolicitarRecuperacionPasswordRequest request = new SolicitarRecuperacionPasswordRequest("existente@test.com");
        Usuario usuario = Usuario.builder().id(1L).email("existente@test.com").build();
        when(usuarioRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(usuario));

        recuperacionPasswordService.solicitarRecuperacion(request);

        verify(tokenRecuperacionPasswordRepository).deleteByEmail("existente@test.com");

        ArgumentCaptor<TokenRecuperacionPassword> tokenCaptor = ArgumentCaptor.forClass(TokenRecuperacionPassword.class);
        verify(tokenRecuperacionPasswordRepository).save(tokenCaptor.capture());
        TokenRecuperacionPassword tokenGuardado = tokenCaptor.getValue();
        assertEquals("existente@test.com", tokenGuardado.getEmail());
        assertTrue(tokenGuardado.getFechaExpiracion().isAfter(LocalDateTime.now()));

        ArgumentCaptor<RecuperacionPasswordSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(RecuperacionPasswordSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("existente@test.com", eventoCaptor.getValue().email());

        // El valor crudo del código/token solo viaja en el evento (dispara el email); en
        // la base solo se persiste su hash (ver M-05 en la auditoría).
        String codigoCrudo = eventoCaptor.getValue().codigo();
        assertEquals(6, codigoCrudo.length());
        assertTrue(codigoCrudo.chars().allMatch(Character::isDigit));
        assertEquals(TokenHasher.sha256Hex(codigoCrudo), tokenGuardado.getCodigoHash());

        String prefijoLink = "http://localhost:5173/restablecer?token=";
        assertTrue(eventoCaptor.getValue().linkRecuperacion().startsWith(prefijoLink));
        String tokenCrudo = eventoCaptor.getValue().linkRecuperacion().substring(prefijoLink.length());
        assertEquals(TokenHasher.sha256Hex(tokenCrudo), tokenGuardado.getTokenHash());
    }

    @Test
    @DisplayName("solicitarRecuperacion_EmailInexistente_NoHaceNadaYNoLanzaExcepcion")
    void solicitarRecuperacion_EmailInexistente_NoHaceNadaYNoLanzaExcepcion() {
        SolicitarRecuperacionPasswordRequest request = new SolicitarRecuperacionPasswordRequest("inexistente@test.com");
        when(usuarioRepository.findByEmail("inexistente@test.com")).thenReturn(Optional.empty());

        recuperacionPasswordService.solicitarRecuperacion(request);

        verify(tokenRecuperacionPasswordRepository, never()).deleteByEmail(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("solicitarRecuperacion_Fallo_RateLimitExcedido")
    void solicitarRecuperacion_Fallo_RateLimitExcedido() {
        SolicitarRecuperacionPasswordRequest request = new SolicitarRecuperacionPasswordRequest("existente@test.com");
        when(rateLimiterService.tryConsume(eq("recuperar-password:existente@test.com"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> recuperacionPasswordService.solicitarRecuperacion(request));

        verify(usuarioRepository, never()).findByEmail(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword_ExitoViaToken_ActualizaPasswordIncrementaTokenVersionYBorraToken")
    void resetPassword_ExitoViaToken_ActualizaPasswordIncrementaTokenVersionYBorraToken() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("existente@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        Usuario usuario = Usuario.builder().id(1L).email("existente@test.com").password("old").tokenVersion(3).build();
        ResetPasswordRequest request = new ResetPasswordRequest("token-valido", null, null, "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.encode("NuevaPass123")).thenReturn("encoded-password");

        recuperacionPasswordService.resetPassword(request);

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        Usuario usuarioGuardado = usuarioCaptor.getValue();
        assertEquals("encoded-password", usuarioGuardado.getPassword());
        assertEquals(4, usuarioGuardado.getTokenVersion());

        verify(tokenRecuperacionPasswordRepository).delete(token);

        ArgumentCaptor<PasswordCambiadaEvent> eventoCaptor = ArgumentCaptor.forClass(PasswordCambiadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("existente@test.com", eventoCaptor.getValue().email());
    }

    @Test
    @DisplayName("resetPassword_ExitoViaEmailYCodigo_ActualizaPassword")
    void resetPassword_ExitoViaEmailYCodigo_ActualizaPassword() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("existente@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        Usuario usuario = Usuario.builder().id(1L).email("existente@test.com").password("old").tokenVersion(0).build();
        ResetPasswordRequest request = new ResetPasswordRequest(null, "existente@test.com", "123456", "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(token));
        when(usuarioRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(usuario));
        when(passwordEncoder.encode("NuevaPass123")).thenReturn("encoded-password");

        recuperacionPasswordService.resetPassword(request);

        verify(usuarioRepository).save(usuario);
        verify(tokenRecuperacionPasswordRepository).delete(token);
        verify(eventPublisher).publishEvent(any(PasswordCambiadaEvent.class));
    }

    @Test
    @DisplayName("resetPassword_Fallo_CodigoIncorrectoIncrementaIntentos")
    void resetPassword_Fallo_CodigoIncorrectoIncrementaIntentos() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("existente@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        ResetPasswordRequest request = new ResetPasswordRequest(null, "existente@test.com", "000000", "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(token));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> recuperacionPasswordService.resetPassword(request));

        assertEquals("Código incorrecto", exception.getMessage());
        ArgumentCaptor<TokenRecuperacionPassword> tokenCaptor = ArgumentCaptor.forClass(TokenRecuperacionPassword.class);
        verify(tokenRecuperacionPasswordRepository).save(tokenCaptor.capture());
        assertEquals(1, tokenCaptor.getValue().getIntentos());
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_IntentosAgotadosBorraTokenYLanzaMensajeDistinto")
    void resetPassword_Fallo_IntentosAgotadosBorraTokenYLanzaMensajeDistinto() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("existente@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(5)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        ResetPasswordRequest request = new ResetPasswordRequest(null, "existente@test.com", "000000", "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByEmail("existente@test.com")).thenReturn(Optional.of(token));

        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class,
                () -> recuperacionPasswordService.resetPassword(request));

        assertEquals("Demasiados intentos, pedí un nuevo código", exception.getMessage());
        verify(tokenRecuperacionPasswordRepository).delete(token);
        verify(tokenRecuperacionPasswordRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_TokenExpirado")
    void resetPassword_Fallo_TokenExpirado() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("existente@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-vencido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().minusMinutes(1))
                .build();
        ResetPasswordRequest request = new ResetPasswordRequest("token-vencido", null, null, "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByTokenHash(TokenHasher.sha256Hex("token-vencido"))).thenReturn(Optional.of(token));

        assertThrows(TokenExpiradoException.class, () -> recuperacionPasswordService.resetPassword(request));

        verify(tokenRecuperacionPasswordRepository).delete(token);
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_TokenInvalido")
    void resetPassword_Fallo_TokenInvalido() {
        ResetPasswordRequest request = new ResetPasswordRequest("token-inexistente", null, null, "NuevaPass123");
        when(tokenRecuperacionPasswordRepository.findByTokenHash(TokenHasher.sha256Hex("token-inexistente"))).thenReturn(Optional.empty());

        assertThrows(TokenInvalidoException.class, () -> recuperacionPasswordService.resetPassword(request));
        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_TokenYEmailCodigoAmbosProvistos")
    void resetPassword_Fallo_TokenYEmailCodigoAmbosProvistos() {
        ResetPasswordRequest request = new ResetPasswordRequest("token-valido", "existente@test.com", "123456", "NuevaPass123");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> recuperacionPasswordService.resetPassword(request));

        assertEquals("Enviá el token o el email+código, no ambos", exception.getMessage());
        verify(tokenRecuperacionPasswordRepository, never()).findByTokenHash(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("resetPassword_Fallo_NiTokenNiEmailCodigoProvistos")
    void resetPassword_Fallo_NiTokenNiEmailCodigoProvistos() {
        ResetPasswordRequest request = new ResetPasswordRequest(null, null, null, "NuevaPass123");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> recuperacionPasswordService.resetPassword(request));

        assertEquals("Enviá el token o el email+código", exception.getMessage());
        verify(tokenRecuperacionPasswordRepository, never()).findByTokenHash(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("solicitarRecuperacion_CuentaEliminada_NoHaceNadaYNoLanzaExcepcion")
    void solicitarRecuperacion_CuentaEliminada_NoHaceNadaYNoLanzaExcepcion() {
        SolicitarRecuperacionPasswordRequest request = new SolicitarRecuperacionPasswordRequest("eliminado@test.com");
        Usuario usuarioEliminado = Usuario.builder()
                .id(1L)
                .email("eliminado@test.com")
                .deletedAt(LocalDateTime.now())
                .build();
        when(usuarioRepository.findByEmail("eliminado@test.com")).thenReturn(Optional.of(usuarioEliminado));

        recuperacionPasswordService.solicitarRecuperacion(request);

        verify(tokenRecuperacionPasswordRepository, never()).deleteByEmail(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_UsuarioYaEliminado")
    void resetPassword_Fallo_UsuarioYaEliminado() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("eliminado@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        Usuario usuarioEliminado = Usuario.builder()
                .id(1L)
                .email("eliminado@test.com")
                .password("hash-random")
                .tokenVersion(5)
                .deletedAt(LocalDateTime.now().minusDays(1))
                .build();
        ResetPasswordRequest request = new ResetPasswordRequest("token-valido", null, null, "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.findByEmail("eliminado@test.com")).thenReturn(Optional.of(usuarioEliminado));

        assertThrows(TokenInvalidoException.class, () -> recuperacionPasswordService.resetPassword(request));

        verify(usuarioRepository, never()).save(any());
        verify(tokenRecuperacionPasswordRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
