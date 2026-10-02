package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.AuthResponse;
import com.matiasmeira.sacaladelangulo.auth.dto.CompletarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.IniciarRegistroRequest;
import com.matiasmeira.sacaladelangulo.auth.dto.TipoRegistro;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistroVerificacionService - Registro de jugadores en 2 pasos")
class RegistroVerificacionServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private TokenVerificacionEmailRepository tokenVerificacionEmailRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private RegistroVerificacionService registroVerificacionService;

    @BeforeEach
    void setUp() {
        lenient().when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);
        ReflectionTestUtils.setField(registroVerificacionService, "frontendUrl", "http://localhost:5173");
    }

    @Test
    @DisplayName("iniciarRegistro_Exito_GeneraTokenYPublicaEventoDeEmail")
    void iniciarRegistro_Exito_GeneraTokenYPublicaEventoDeEmail() {
        IniciarRegistroRequest request = new IniciarRegistroRequest("nuevo@test.com");
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);

        registroVerificacionService.iniciarRegistro(request);

        verify(tokenVerificacionEmailRepository).deleteByEmail("nuevo@test.com");

        ArgumentCaptor<TokenVerificacionEmail> tokenCaptor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(tokenCaptor.capture());
        TokenVerificacionEmail tokenGuardado = tokenCaptor.getValue();
        assertEquals("nuevo@test.com", tokenGuardado.getEmail());
        assertTrue(tokenGuardado.getFechaExpiracion().isAfter(LocalDateTime.now()));

        ArgumentCaptor<VerificacionEmailSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("nuevo@test.com", eventoCaptor.getValue().email());

        // El valor crudo del código/token solo viaja en el evento (dispara el email); en
        // la base solo se persiste su hash.
        String codigoCrudo = eventoCaptor.getValue().codigo();
        assertEquals(6, codigoCrudo.length());
        assertTrue(codigoCrudo.chars().allMatch(Character::isDigit));
        assertEquals(TokenHasher.sha256Hex(codigoCrudo), tokenGuardado.getCodigoHash());

        String prefijoLink = "http://localhost:5173/verificar?token=";
        assertTrue(eventoCaptor.getValue().linkVerificacion().startsWith(prefijoLink));
        String tokenCrudo = eventoCaptor.getValue().linkVerificacion().substring(prefijoLink.length());
        assertEquals(TokenHasher.sha256Hex(tokenCrudo), tokenGuardado.getTokenHash());
    }

    private static final String VOLVER_A = "/reservar/x?cancha=3&inicio=2026-01-01T10:00:00-03:00";

    private VerificacionEmailSolicitadaEvent iniciarYCapturarEvento(IniciarRegistroRequest request) {
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);
        registroVerificacionService.iniciarRegistro(request);
        ArgumentCaptor<VerificacionEmailSolicitadaEvent> captor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("iniciarRegistro_ConVolverAValido_LoAgregaEncodeadoAlLink")
    void iniciarRegistro_ConVolverAValido_LoAgregaEncodeadoAlLink() {
        VerificacionEmailSolicitadaEvent evento = iniciarYCapturarEvento(new IniciarRegistroRequest("nuevo@test.com", VOLVER_A));

        String link = evento.linkVerificacion();
        String prefijo = "http://localhost:5173/verificar?token=";
        assertTrue(link.startsWith(prefijo));
        int corte = link.indexOf("&volverA=");
        assertTrue(corte > prefijo.length(), "El link debe llevar token y luego volverA");
        String encodeado = link.substring(corte + "&volverA=".length());
        assertFalse(encodeado.contains("&") || encodeado.contains("?") || encodeado.contains("/"),
                "volverA debe ir URL-encoded");
        assertEquals(VOLVER_A, java.net.URLDecoder.decode(encodeado, java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("iniciarRegistro_SinVolverA_LinkIdenticoAlActual")
    void iniciarRegistro_SinVolverA_LinkIdenticoAlActual() {
        String link = iniciarYCapturarEvento(new IniciarRegistroRequest("nuevo@test.com")).linkVerificacion();

        assertFalse(link.contains("volverA"));
        assertTrue(link.matches("http://localhost:5173/verificar\\?token=[0-9a-f-]{36}"));
    }

    @Test
    @DisplayName("iniciarRegistro_ConVolverAInvalido_LoIgnoraSinError")
    void iniciarRegistro_ConVolverAInvalido_LoIgnoraSinError() {
        String link = iniciarYCapturarEvento(new IniciarRegistroRequest("nuevo@test.com", "//evil.com")).linkVerificacion();

        assertFalse(link.contains("volverA"));
        assertTrue(link.matches("http://localhost:5173/verificar\\?token=[0-9a-f-]{36}"));
    }

    @Test
    @DisplayName("iniciarRegistro_ConVolverA_LoPersistidoEsElMismoQueSinVolverA")
    void iniciarRegistro_ConVolverA_LoPersistidoEsElMismoQueSinVolverA() {
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);

        registroVerificacionService.iniciarRegistro(new IniciarRegistroRequest("nuevo@test.com", VOLVER_A));

        ArgumentCaptor<TokenVerificacionEmail> tokenCaptor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(tokenCaptor.capture());
        ArgumentCaptor<VerificacionEmailSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        TokenVerificacionEmail guardado = tokenCaptor.getValue();

        String link = eventoCaptor.getValue().linkVerificacion();
        String tokenCrudo = link.substring(link.indexOf("token=") + 6, link.indexOf("&volverA="));
        // El hash es el del token solo (sin volverA): lo persistido no cambia con volverA.
        assertEquals(TokenHasher.sha256Hex(tokenCrudo), guardado.getTokenHash());
        assertEquals(TokenHasher.sha256Hex(eventoCaptor.getValue().codigo()), guardado.getCodigoHash());
        assertEquals("nuevo@test.com", guardado.getEmail());
    }

    @Test
    @DisplayName("iniciarRegistro_ConFrontendUrlConSlashFinal_NoDuplicaElSlashEnElLink")
    void iniciarRegistro_ConFrontendUrlConSlashFinal_NoDuplicaElSlashEnElLink() {
        ReflectionTestUtils.setField(registroVerificacionService, "frontendUrl", "http://localhost:3000/");
        IniciarRegistroRequest request = new IniciarRegistroRequest("nuevo@test.com");
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);

        registroVerificacionService.iniciarRegistro(request);

        ArgumentCaptor<VerificacionEmailSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertTrue(eventoCaptor.getValue().linkVerificacion().startsWith("http://localhost:3000/verificar?token="),
                "Un app.frontend-url con \"/\" final no debe producir \"//\" en el link");
    }

    @Test
    @DisplayName("iniciarRegistro_Fallo_EmailYaRegistrado")
    void iniciarRegistro_Fallo_EmailYaRegistrado() {
        IniciarRegistroRequest request = new IniciarRegistroRequest("existente@test.com");
        when(usuarioRepository.existsByEmail("existente@test.com")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registroVerificacionService.iniciarRegistro(request));

        assertEquals("El email ya está registrado", exception.getMessage());
        verify(tokenVerificacionEmailRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("iniciarRegistro_Fallo_RateLimitExcedido")
    void iniciarRegistro_Fallo_RateLimitExcedido() {
        IniciarRegistroRequest request = new IniciarRegistroRequest("nuevo@test.com");
        when(rateLimiterService.tryConsume(eq("registro-email:nuevo@test.com"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> registroVerificacionService.iniciarRegistro(request));

        verify(usuarioRepository, never()).existsByEmail(anyString());
        verify(tokenVerificacionEmailRepository, never()).save(any());
    }

    @Test
    @DisplayName("verificarToken_Exito_TokenValido")
    void verificarToken_Exito_TokenValido() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));

        VerificarTokenResponse response = registroVerificacionService.verificarToken("token-valido");

        assertEquals("nuevo@test.com", response.email());
        assertTrue(response.verificado());
        verify(tokenVerificacionEmailRepository, never()).delete(any());
    }

    @Test
    @DisplayName("verificarToken_Fallo_TokenInexistente")
    void verificarToken_Fallo_TokenInexistente() {
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-inexistente"))).thenReturn(Optional.empty());

        assertThrows(TokenInvalidoException.class, () -> registroVerificacionService.verificarToken("token-inexistente"));
    }

    @Test
    @DisplayName("verificarToken_Fallo_TokenExpirado")
    void verificarToken_Fallo_TokenExpirado() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-vencido"))
                .fechaExpiracion(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-vencido"))).thenReturn(Optional.of(token));

        assertThrows(TokenExpiradoException.class, () -> registroVerificacionService.verificarToken("token-vencido"));

        verify(tokenVerificacionEmailRepository, times(1)).delete(token);
    }

    @Test
    @DisplayName("completarRegistro_Exito_CreaUsuarioYDevuelveToken")
    void completarRegistro_Exito_CreaUsuarioYDevuelveToken() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        CompletarRegistroRequest request = new CompletarRegistroRequest("token-valido", "Juan", "1122334455", "Password123");

        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123")).thenReturn("encoded-password");
        // El UserDetails real es un UsuarioPrincipal construido internamente a partir del
        // Usuario recién creado (ver UsuarioUserDetailsMapper); no hay forma de construir
        // acá una instancia igual a la que arma el código, así que se matchea por tipo.
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("jwt-token");

        AuthResponse response = registroVerificacionService.completarRegistro(request);

        assertEquals("jwt-token", response.token());
        verify(tokenVerificacionEmailRepository).delete(token);

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).saveAndFlush(usuarioCaptor.capture());
        Usuario usuarioGuardado = usuarioCaptor.getValue();
        assertEquals("nuevo@test.com", usuarioGuardado.getEmail());
        assertTrue(usuarioGuardado.getEmailVerified());
        assertTrue(usuarioGuardado.getIsActive());

        ArgumentCaptor<RegistroCompletadoEvent> eventoCaptor = ArgumentCaptor.forClass(RegistroCompletadoEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("nuevo@test.com", eventoCaptor.getValue().email());
        assertEquals("Juan", eventoCaptor.getValue().nombre());
    }

    @Test
    @DisplayName("completarRegistro_Fallo_TokenInvalido")
    void completarRegistro_Fallo_TokenInvalido() {
        CompletarRegistroRequest request = new CompletarRegistroRequest("token-inexistente", "Juan", null, "Password123");
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-inexistente"))).thenReturn(Optional.empty());

        assertThrows(TokenInvalidoException.class, () -> registroVerificacionService.completarRegistro(request));
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("completarRegistro_Fallo_TokenExpirado")
    void completarRegistro_Fallo_TokenExpirado() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-vencido"))
                .fechaExpiracion(LocalDateTime.now().minusMinutes(1))
                .build();
        CompletarRegistroRequest request = new CompletarRegistroRequest("token-vencido", "Juan", null, "Password123");
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-vencido"))).thenReturn(Optional.of(token));

        assertThrows(TokenExpiradoException.class, () -> registroVerificacionService.completarRegistro(request));
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("completarRegistro_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocioYBorraToken")
    void completarRegistro_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocioYBorraToken() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        CompletarRegistroRequest request = new CompletarRegistroRequest("token-valido", "Juan", null, "Password123");

        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);
        when(usuarioRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registroVerificacionService.completarRegistro(request));

        assertEquals("El email ya está registrado", exception.getMessage());
        verify(tokenVerificacionEmailRepository).delete(token);
    }

    @Test
    @DisplayName("completarRegistro_Fallo_EmailYaRegistradoEntreVerificacionYCompletar")
    void completarRegistro_Fallo_EmailYaRegistradoEntreVerificacionYCompletar() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        CompletarRegistroRequest request = new CompletarRegistroRequest("token-valido", "Juan", null, "Password123");

        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registroVerificacionService.completarRegistro(request));

        assertEquals("El email ya está registrado", exception.getMessage());
        verify(tokenVerificacionEmailRepository).delete(token);
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("verificarCodigo_Exito_EmiteUnNuevoTokenYPersisteSoloSuHash")
    void verificarCodigo_Exito_EmiteUnNuevoTokenYPersisteSoloSuHash() {
        // El código validado no tiene un token crudo recuperable (solo se persiste su
        // hash): verificarCodigo emite un token NUEVO
        // (equivalente al del link) y actualiza el hash persistido a partir de él.
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-original-del-link"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(token));

        VerificarCodigoRegistroResponse response = registroVerificacionService.verificarCodigo("nuevo@test.com", "123456");

        assertTrue(response.token() != null && !response.token().isBlank());
        ArgumentCaptor<TokenVerificacionEmail> tokenCaptor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(tokenCaptor.capture());
        assertEquals(TokenHasher.sha256Hex(response.token()), tokenCaptor.getValue().getTokenHash());
        verify(tokenVerificacionEmailRepository, never()).delete(any());
    }

    @Test
    @DisplayName("verificarCodigo_Fallo_CodigoIncorrectoIncrementaIntentos")
    void verificarCodigo_Fallo_CodigoIncorrectoIncrementaIntentos() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(token));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> registroVerificacionService.verificarCodigo("nuevo@test.com", "000000"));

        assertEquals("Código incorrecto", exception.getMessage());
        ArgumentCaptor<TokenVerificacionEmail> tokenCaptor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(tokenCaptor.capture());
        assertEquals(1, tokenCaptor.getValue().getIntentos());
        // El intento fallido no debe tocar el tokenHash, solo el contador de intentos.
        assertEquals(TokenHasher.sha256Hex("token-valido"), tokenCaptor.getValue().getTokenHash());
        verify(tokenVerificacionEmailRepository, never()).delete(any());
    }

    @Test
    @DisplayName("verificarCodigo_Fallo_IntentosAgotadosBorraTokenYLanzaMensajeDistinto")
    void verificarCodigo_Fallo_IntentosAgotadosBorraTokenYLanzaMensajeDistinto() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(5)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(token));

        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class,
                () -> registroVerificacionService.verificarCodigo("nuevo@test.com", "000000"));

        assertEquals("Demasiados intentos, pedí un nuevo código", exception.getMessage());
        verify(tokenVerificacionEmailRepository).delete(token);
        verify(tokenVerificacionEmailRepository, never()).save(any());
    }

    @Test
    @DisplayName("verificarCodigo_Fallo_TokenInexistente")
    void verificarCodigo_Fallo_TokenInexistente() {
        when(tokenVerificacionEmailRepository.findByEmail("inexistente@test.com")).thenReturn(Optional.empty());

        assertThrows(TokenInvalidoException.class,
                () -> registroVerificacionService.verificarCodigo("inexistente@test.com", "123456"));
    }

    @Test
    @DisplayName("verificarCodigo_Fallo_TokenExpirado")
    void verificarCodigo_Fallo_TokenExpirado() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-vencido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().minusMinutes(1))
                .build();
        when(tokenVerificacionEmailRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(token));

        assertThrows(TokenExpiradoException.class,
                () -> registroVerificacionService.verificarCodigo("nuevo@test.com", "123456"));

        verify(tokenVerificacionEmailRepository).delete(token);
    }

    @Test
    @DisplayName("verificarCodigo_Fallo_RateLimitExcedido")
    void verificarCodigo_Fallo_RateLimitExcedido() {
        when(rateLimiterService.tryConsume(eq("verificar-codigo:nuevo@test.com"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> registroVerificacionService.verificarCodigo("nuevo@test.com", "123456"));

        verify(tokenVerificacionEmailRepository, never()).findByEmail(anyString());
    }

    // ---- Tipo de registro (jugador / dueño) ----

    private TokenVerificacionEmail iniciarYCapturarToken(IniciarRegistroRequest request) {
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);
        registroVerificacionService.iniciarRegistro(request);
        ArgumentCaptor<TokenVerificacionEmail> captor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("iniciarRegistro_TipoDueno_GuardaRolOwnerEnElTokenYLoLlevaEnElEvento")
    void iniciarRegistro_TipoDueno_GuardaRolOwnerEnElTokenYLoLlevaEnElEvento() {
        TokenVerificacionEmail guardado = iniciarYCapturarToken(
                new IniciarRegistroRequest("nuevo@test.com", null, TipoRegistro.DUENO));

        assertEquals(Role.OWNER, guardado.getRol());
        ArgumentCaptor<VerificacionEmailSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(Role.OWNER, eventoCaptor.getValue().rol());
    }

    @Test
    @DisplayName("iniciarRegistro_TipoJugador_GuardaRolPlayer")
    void iniciarRegistro_TipoJugador_GuardaRolPlayer() {
        TokenVerificacionEmail guardado = iniciarYCapturarToken(
                new IniciarRegistroRequest("nuevo@test.com", null, TipoRegistro.JUGADOR));

        assertEquals(Role.PLAYER, guardado.getRol());
    }

    @Test
    @DisplayName("iniciarRegistro_SinTipo_GuardaRolPlayerYElEventoLoLleva")
    void iniciarRegistro_SinTipo_GuardaRolPlayerYElEventoLoLleva() {
        TokenVerificacionEmail guardado = iniciarYCapturarToken(new IniciarRegistroRequest("nuevo@test.com"));

        assertEquals(Role.PLAYER, guardado.getRol());
        ArgumentCaptor<VerificacionEmailSolicitadaEvent> eventoCaptor = ArgumentCaptor.forClass(VerificacionEmailSolicitadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(Role.PLAYER, eventoCaptor.getValue().rol());
    }

    @Test
    @DisplayName("verificarCodigo_RotaElTokenYConservaElRolDelPendiente")
    void verificarCodigo_RotaElTokenYConservaElRolDelPendiente() {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-original"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .rol(Role.OWNER)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByEmail("nuevo@test.com")).thenReturn(Optional.of(token));

        VerificarCodigoRegistroResponse response = registroVerificacionService.verificarCodigo("nuevo@test.com", "123456");

        ArgumentCaptor<TokenVerificacionEmail> tokenCaptor = ArgumentCaptor.forClass(TokenVerificacionEmail.class);
        verify(tokenVerificacionEmailRepository).save(tokenCaptor.capture());
        assertEquals(TokenHasher.sha256Hex(response.token()), tokenCaptor.getValue().getTokenHash());
        assertEquals(Role.OWNER, tokenCaptor.getValue().getRol());
    }

    private Usuario completarConPendiente(Role rolDelToken) {
        TokenVerificacionEmail token = TokenVerificacionEmail.builder()
                .id(1L)
                .email("nuevo@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .rol(rolDelToken)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        when(tokenVerificacionEmailRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.existsByEmail("nuevo@test.com")).thenReturn(false);
        when(passwordEncoder.encode("Password123")).thenReturn("encoded-password");
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("jwt-token");

        registroVerificacionService.completarRegistro(
                new CompletarRegistroRequest("token-valido", "Juan", "1122334455", "Password123"));

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).saveAndFlush(usuarioCaptor.capture());
        return usuarioCaptor.getValue();
    }

    @Test
    @DisplayName("completarRegistro_PendienteOwner_CreaOwnerConTrialSinFechaFinYEmailVerificado")
    void completarRegistro_PendienteOwner_CreaOwnerConTrialSinFechaFinYEmailVerificado() {
        Usuario guardado = completarConPendiente(Role.OWNER);

        assertEquals(Role.OWNER, guardado.getRol());
        assertEquals(PlanSuscripcion.TRIAL, guardado.getPlanSuscripcion());
        assertNull(guardado.getFechaFinPrueba());
        assertTrue(guardado.getEmailVerified());
        assertTrue(guardado.getIsActive());

        ArgumentCaptor<RegistroCompletadoEvent> eventoCaptor = ArgumentCaptor.forClass(RegistroCompletadoEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(Role.OWNER, eventoCaptor.getValue().rol());
    }

    @Test
    @DisplayName("completarRegistro_PendientePlayer_CreaPlayerConPlanFree")
    void completarRegistro_PendientePlayer_CreaPlayerConPlanFree() {
        Usuario guardado = completarConPendiente(Role.PLAYER);

        assertEquals(Role.PLAYER, guardado.getRol());
        assertEquals(PlanSuscripcion.FREE, guardado.getPlanSuscripcion());
        assertNull(guardado.getFechaFinPrueba());
        assertTrue(guardado.getEmailVerified());

        ArgumentCaptor<RegistroCompletadoEvent> eventoCaptor = ArgumentCaptor.forClass(RegistroCompletadoEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(Role.PLAYER, eventoCaptor.getValue().rol());
    }
}
