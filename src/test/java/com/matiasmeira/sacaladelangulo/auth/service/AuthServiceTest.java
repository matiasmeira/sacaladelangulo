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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService - Registro y autenticación")
class AuthServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RateLimiterService rateLimiterService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        // Default: siempre hay intentos disponibles. Los tests que necesiten simular
        // el límite agotado lo overridean explícitamente.
        lenient().when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("authenticate_Exito_GeneraToken")
    void authenticate_Exito_GeneraToken() {
        AuthRequest request = new AuthRequest("jugador@test.com", "Password123");
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(request.email())
                .password(request.password())
                .authorities("ROLE_PLAYER")
                .build();

        when(jwtService.generateToken(userDetails)).thenReturn("jwt-token");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));

        AuthResponse response = authService.authenticate(request);

        assertEquals("jwt-token", response.token());
    }

    @Test
    @DisplayName("authenticate_Fallo_CredencialesInvalidas")
    void authenticate_Fallo_CredencialesInvalidas() {
        AuthRequest request = new AuthRequest("jugador@test.com", "Password123");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Credenciales inválidas"));

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> authService.authenticate(request)
        );

        assertEquals("Credenciales inválidas", exception.getMessage());
    }

    @Test
    @DisplayName("authenticateEmpleado_Exito_GeneraTokenConClaimDeEmpleadoId")
    void authenticateEmpleado_Exito_GeneraTokenConClaimDeEmpleadoId() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Juan", "1234");
        Usuario empleado = Usuario.builder()
                .id(5L)
                .email("empleado-uuid@empleados.sacaladelangulo.interno")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .build();
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(empleado.getEmail())
                .password("hash-1234")
                .authorities("ROLE_EMPLOYEE")
                .build();

        when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(10L, "juan", Role.EMPLOYEE))
                .thenReturn(Optional.of(empleado));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        when(jwtService.generateToken(eq(userDetails), anyMap(), anyLong())).thenReturn("jwt-token-empleado");

        AuthResponse response = authService.authenticateEmpleado(request, 10L);

        assertEquals("jwt-token-empleado", response.token());
        verify(jwtService).generateToken(eq(userDetails), eq(Map.of("empleadoId", empleado.getId())), anyLong());
    }

    @Test
    @DisplayName("authenticateEmpleado_Fallo_EmpleadoNoEncontrado")
    void authenticateEmpleado_Fallo_EmpleadoNoEncontrado() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Fantasma", "1234");
        when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(10L, "fantasma", Role.EMPLOYEE))
                .thenReturn(Optional.empty());

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> authService.authenticateEmpleado(request, 10L)
        );

        assertEquals("Credenciales inválidas", exception.getMessage());
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("authenticateEmpleado_Fallo_EmpleadoInactivo")
    void authenticateEmpleado_Fallo_EmpleadoInactivo() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Juan", "1234");

        // Un empleado desactivado ya no lo devuelve la consulta: el filtro AndIsActiveTrue
        // lo excluye en la base, en vez de traerlo y descartarlo después en el service. El
        // efecto para quien intenta entrar es el mismo (credenciales inválidas), pero así el
        // finder mira el mismo conjunto que el guard de unicidad del alta y puede seguir
        // declarando Optional aunque haya un homónimo de baja (ver AuthServiceEmpleadoHomonimoTest).
        when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(10L, "juan", Role.EMPLOYEE))
                .thenReturn(Optional.empty());

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> authService.authenticateEmpleado(request, 10L)
        );

        assertEquals("Credenciales inválidas", exception.getMessage());
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("authenticateEmpleado_Fallo_PinIncorrecto")
    void authenticateEmpleado_Fallo_PinIncorrecto() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Juan", "0000");
        Usuario empleado = Usuario.builder()
                .id(5L)
                .email("empleado-uuid@empleados.sacaladelangulo.interno")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .build();

        when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(10L, "juan", Role.EMPLOYEE))
                .thenReturn(Optional.of(empleado));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> authService.authenticateEmpleado(request, 10L)
        );

        assertEquals("Credenciales inválidas", exception.getMessage());
    }

    @Test
    @DisplayName("authenticate_Fallo_LimiteDeIntentosSuperado")
    void authenticate_Fallo_LimiteDeIntentosSuperado() {
        AuthRequest request = new AuthRequest("jugador@test.com", "Password123");
        when(rateLimiterService.tryConsume(eq("login:jugador@test.com"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> authService.authenticate(request)
        );
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("registerOwner_Fallo_LimiteDeIntentosSuperado")
    void registerOwner_Fallo_LimiteDeIntentosSuperado() {
        RegisterRequest request = new RegisterRequest("dueno@test.com", "Password123", "Carlos");
        when(rateLimiterService.tryConsume(eq("register-owner:dueno@test.com"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> authService.registerOwner(request)
        );
        verify(usuarioRepository, never()).existsByEmail(anyString());
        verify(usuarioRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("registerOwner_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio")
    void registerOwner_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio() {
        RegisterRequest request = new RegisterRequest("dueno@test.com", "Password123", "Carlos");
        when(usuarioRepository.existsByEmail("dueno@test.com")).thenReturn(false);
        when(usuarioRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.registerOwner(request)
        );

        assertEquals("El email ya está registrado", exception.getMessage());
    }

    @Test
    @DisplayName("authenticateEmpleado_Fallo_LimiteDeIntentosSuperado")
    void authenticateEmpleado_Fallo_LimiteDeIntentosSuperado() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Juan", "1234");
        when(rateLimiterService.tryConsume(eq("login-empleado:10:juan"), anyInt(), anyLong())).thenReturn(false);

        assertThrows(
                RateLimitExceededException.class,
                () -> authService.authenticateEmpleado(request, 10L)
        );
        verify(usuarioRepository, never()).findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(any(), any(), any());
    }

    @Test
    @DisplayName("authenticateEmpleado_Fallo_EstablecimientoIdDelBodyNoCoincideConElDelDispositivo")
    void authenticateEmpleado_Fallo_EstablecimientoIdDelBodyNoCoincideConElDelDispositivo() {
        // El body trae establecimientoId=10, pero el dispositivo de caja (fuente de
        // verdad) resolvió establecimientoId=20: se rechaza sin siquiera consultar rate
        // limit/base, con el mismo error genérico que cualquier otro fallo de login.
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(10L, "Juan", "1234");

        BadCredentialsException exception = assertThrows(
                BadCredentialsException.class,
                () -> authService.authenticateEmpleado(request, 20L)
        );

        assertEquals("Credenciales inválidas", exception.getMessage());
        verify(usuarioRepository, never()).findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(any(), any(), any());
    }

    @Test
    @DisplayName("authenticateEmpleado_Exito_SinEstablecimientoIdEnElBody_UsaElDelDispositivo")
    void authenticateEmpleado_Exito_SinEstablecimientoIdEnElBody_UsaElDelDispositivo() {
        EmpleadoLoginRequest request = new EmpleadoLoginRequest(null, "Juan", "1234");
        Usuario empleado = Usuario.builder()
                .id(5L)
                .email("empleado-uuid@empleados.sacaladelangulo.interno")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .build();
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername(empleado.getEmail())
                .password("hash-1234")
                .authorities("ROLE_EMPLOYEE")
                .build();

        when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(10L, "juan", Role.EMPLOYEE))
                .thenReturn(Optional.of(empleado));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        when(jwtService.generateToken(eq(userDetails), anyMap(), anyLong())).thenReturn("jwt-token-empleado");

        AuthResponse response = authService.authenticateEmpleado(request, 10L);

        assertEquals("jwt-token-empleado", response.token());
    }

    @Test
    @DisplayName("logout_Exito_IncrementaTokenVersion")
    void logout_Exito_IncrementaTokenVersion() {
        Usuario usuario = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .rol(Role.PLAYER)
                .tokenVersion(3)
                .build();
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(usuario));
        when(usuarioRepository.save(any(Usuario.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.logout("jugador@test.com");

        assertEquals(4, usuario.getTokenVersion());
        verify(usuarioRepository).save(usuario);
    }

    @Test
    @DisplayName("logout_Fallo_UsuarioNoEncontrado")
    void logout_Fallo_UsuarioNoEncontrado() {
        when(usuarioRepository.findByEmail("fantasma@test.com")).thenReturn(Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> authService.logout("fantasma@test.com")
        );
        verify(usuarioRepository, never()).save(any());
    }
}
