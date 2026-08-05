package com.matiasmeira.sacaladelangulo.caja.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalRequest;
import com.matiasmeira.sacaladelangulo.caja.dto.ActivarLocalResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.ConsumirCodigoResponse;
import com.matiasmeira.sacaladelangulo.caja.dto.DispositivoCajaResponse;
import com.matiasmeira.sacaladelangulo.caja.model.CodigoEmparejamientoCaja;
import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import com.matiasmeira.sacaladelangulo.caja.repository.CodigoEmparejamientoCajaRepository;
import com.matiasmeira.sacaladelangulo.caja.repository.DispositivoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitExceededException;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimiterService;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DispositivoCajaService - Tests de emparejamiento y validación de dispositivos de caja")
class DispositivoCajaServiceTest {

    @Mock
    private DispositivoCajaRepository dispositivoCajaRepository;

    @Mock
    private CodigoEmparejamientoCajaRepository codigoEmparejamientoCajaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    @Mock
    private HttpServletResponse httpServletResponse;

    private DispositivoCajaService dispositivoCajaService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dispositivoCajaService = new DispositivoCajaService(
                dispositivoCajaRepository, codigoEmparejamientoCajaRepository, establecimientoRepository,
                autorizacionEmpleadoService, rateLimiterService, registroAuditoriaService);
        ReflectionTestUtils.setField(dispositivoCajaService, "dispositivoExpirationMillis", 7_776_000_000L);
        ReflectionTestUtils.setField(dispositivoCajaService, "codigoEmparejamientoTtlMillis", 600_000L);
        ReflectionTestUtils.setField(dispositivoCajaService, "frontendUrl", "http://localhost:5173");

        lenient().when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);

        dueno = Usuario.builder().id(2L).email("dueno@test.com").rol(Role.OWNER).build();
        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("activarLocal_Exito_CreaDispositivoYSeteaCookie")
    void activarLocal_Exito_CreaDispositivoYSeteaCookie() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(dispositivoCajaRepository.save(any(DispositivoCaja.class))).thenAnswer(invocation -> {
            DispositivoCaja d = invocation.getArgument(0);
            d.setId(100L);
            return d;
        });

        ActivarLocalResponse response = assertDoesNotThrow(() ->
                dispositivoCajaService.activarLocal(10L, dueno.getEmail(), new ActivarLocalRequest("Caja 1"), httpServletResponse));

        assertEquals(100L, response.dispositivoId());
        assertEquals("Caja 1", response.label());
        verify(httpServletResponse).addHeader(org.mockito.ArgumentMatchers.eq("Set-Cookie"),
                org.mockito.ArgumentMatchers.contains("saque_caja_device="));
        verify(registroAuditoriaService).registrarDispositivo(
                org.mockito.ArgumentMatchers.eq(dueno), org.mockito.ArgumentMatchers.eq(establecimiento),
                org.mockito.ArgumentMatchers.eq(AccionAuditoria.ACTIVAR_DISPOSITIVO_CAJA), any(), any());
    }

    @Test
    @DisplayName("revocar_Exito_MarcaInactivo")
    void revocar_Exito_MarcaInactivo() {
        DispositivoCaja dispositivo = DispositivoCaja.builder()
                .id(100L).establecimiento(establecimiento).label("Caja 1").tokenHash("hash").activo(true).build();

        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(dispositivoCajaRepository.findByIdAndEstablecimientoId(100L, 10L)).thenReturn(Optional.of(dispositivo));

        assertDoesNotThrow(() -> dispositivoCajaService.revocar(10L, 100L, dueno.getEmail()));

        assertFalse(dispositivo.getActivo());
        verify(dispositivoCajaRepository).save(dispositivo);
    }

    @Test
    @DisplayName("revocar_Fallo_NoAutorizado")
    void revocar_Fallo_NoAutorizado() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();

        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> dispositivoCajaService.revocar(10L, 100L, otroDueno.getEmail()));
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("listar_Exito")
    void listar_Exito() {
        DispositivoCaja dispositivo = DispositivoCaja.builder()
                .id(100L).establecimiento(establecimiento).label("Caja 1").tokenHash("hash").activo(true)
                .createdAt(LocalDateTime.now()).build();

        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(dispositivoCajaRepository.findByEstablecimientoIdAndActivoTrue(10L)).thenReturn(List.of(dispositivo));

        List<DispositivoCajaResponse> response = dispositivoCajaService.listar(10L, dueno.getEmail());

        assertEquals(1, response.size());
        assertEquals("Caja 1", response.get(0).label());
    }

    @Test
    @DisplayName("consumirCodigo_Exito_CreaDispositivoYSeteaCookie")
    void consumirCodigo_Exito_CreaDispositivoYSeteaCookie() {
        CodigoEmparejamientoCaja codigo = CodigoEmparejamientoCaja.builder()
                .id(1L).codigoHash("hash-codigo").establecimiento(establecimiento).label("Caja remota")
                .creadoPor(dueno).expiraEn(LocalDateTime.now().plusMinutes(5)).usado(false).build();

        // El hash real depende del código crudo generado internamente, así que se
        // mockea la búsqueda por hash para devolver el código sin importar cuál sea.
        when(codigoEmparejamientoCajaRepository.findByCodigoHash(anyString())).thenReturn(Optional.of(codigo));
        when(dispositivoCajaRepository.save(any(DispositivoCaja.class))).thenAnswer(invocation -> {
            DispositivoCaja d = invocation.getArgument(0);
            d.setId(200L);
            return d;
        });

        ConsumirCodigoResponse response = assertDoesNotThrow(() ->
                dispositivoCajaService.consumirCodigo("cualquier-codigo-crudo", "127.0.0.1", httpServletResponse));

        assertEquals(10L, response.establecimientoId());
        assertEquals("Caja remota", response.label());
        assertEquals(true, codigo.getUsado());
        verify(codigoEmparejamientoCajaRepository).save(codigo);
        verify(httpServletResponse).addHeader(org.mockito.ArgumentMatchers.eq("Set-Cookie"),
                org.mockito.ArgumentMatchers.contains("saque_caja_device="));
        verify(registroAuditoriaService).registrarDispositivo(
                org.mockito.ArgumentMatchers.eq(dueno), org.mockito.ArgumentMatchers.eq(establecimiento),
                org.mockito.ArgumentMatchers.eq(AccionAuditoria.EMPAREJAR_DISPOSITIVO_CAJA), any(), any());
    }

    @Test
    @DisplayName("consumirCodigo_CodigoInexistente_LanzaAccessDeniedGenerico")
    void consumirCodigo_CodigoInexistente_LanzaAccessDeniedGenerico() {
        when(codigoEmparejamientoCajaRepository.findByCodigoHash(anyString())).thenReturn(Optional.empty());

        AccessDeniedException exception = assertThrows(AccessDeniedException.class,
                () -> dispositivoCajaService.consumirCodigo("no-existe", "127.0.0.1", httpServletResponse));

        assertEquals("Código de emparejamiento inválido", exception.getMessage());
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumirCodigo_CodigoExpirado_LanzaMismoErrorGenerico")
    void consumirCodigo_CodigoExpirado_LanzaMismoErrorGenerico() {
        CodigoEmparejamientoCaja codigoExpirado = CodigoEmparejamientoCaja.builder()
                .id(1L).codigoHash("hash").establecimiento(establecimiento).creadoPor(dueno)
                .expiraEn(LocalDateTime.now().minusMinutes(1)).usado(false).build();
        when(codigoEmparejamientoCajaRepository.findByCodigoHash(anyString())).thenReturn(Optional.of(codigoExpirado));

        AccessDeniedException exception = assertThrows(AccessDeniedException.class,
                () -> dispositivoCajaService.consumirCodigo("codigo", "127.0.0.1", httpServletResponse));

        assertEquals("Código de emparejamiento inválido", exception.getMessage());
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumirCodigo_CodigoYaUsado_LanzaMismoErrorGenerico")
    void consumirCodigo_CodigoYaUsado_LanzaMismoErrorGenerico() {
        CodigoEmparejamientoCaja codigoUsado = CodigoEmparejamientoCaja.builder()
                .id(1L).codigoHash("hash").establecimiento(establecimiento).creadoPor(dueno)
                .expiraEn(LocalDateTime.now().plusMinutes(5)).usado(true).build();
        when(codigoEmparejamientoCajaRepository.findByCodigoHash(anyString())).thenReturn(Optional.of(codigoUsado));

        AccessDeniedException exception = assertThrows(AccessDeniedException.class,
                () -> dispositivoCajaService.consumirCodigo("codigo", "127.0.0.1", httpServletResponse));

        assertEquals("Código de emparejamiento inválido", exception.getMessage());
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumirCodigo_Fallo_LimiteDeIntentosSuperado")
    void consumirCodigo_Fallo_LimiteDeIntentosSuperado() {
        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> dispositivoCajaService.consumirCodigo("codigo", "127.0.0.1", httpServletResponse));
        verify(codigoEmparejamientoCajaRepository, never()).findByCodigoHash(any());
    }

    @Test
    @DisplayName("validarToken_DispositivoActivo_ActualizaLastUsedAt")
    void validarToken_DispositivoActivo_ActualizaLastUsedAt() {
        DispositivoCaja dispositivo = DispositivoCaja.builder()
                .id(100L).establecimiento(establecimiento).label("Caja 1").tokenHash("hash").activo(true).build();
        when(dispositivoCajaRepository.findByTokenHash(anyString())).thenReturn(Optional.of(dispositivo));
        when(dispositivoCajaRepository.save(any(DispositivoCaja.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DispositivoCaja resultado = dispositivoCajaService.validarToken("token-crudo");

        assertEquals(100L, resultado.getId());
        assertEquals(10L, resultado.getEstablecimiento().getId());
        org.junit.jupiter.api.Assertions.assertNotNull(resultado.getLastUsedAt());
    }

    @Test
    @DisplayName("validarToken_DispositivoRevocado_LanzaAccessDenied")
    void validarToken_DispositivoRevocado_LanzaAccessDenied() {
        DispositivoCaja dispositivoRevocado = DispositivoCaja.builder()
                .id(100L).establecimiento(establecimiento).label("Caja 1").tokenHash("hash").activo(false).build();
        when(dispositivoCajaRepository.findByTokenHash(anyString())).thenReturn(Optional.of(dispositivoRevocado));

        assertThrows(AccessDeniedException.class, () -> dispositivoCajaService.validarToken("token-crudo"));
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("validarToken_TokenInexistente_LanzaAccessDenied")
    void validarToken_TokenInexistente_LanzaAccessDenied() {
        when(dispositivoCajaRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThrows(AccessDeniedException.class, () -> dispositivoCajaService.validarToken("token-crudo"));
    }

    @Test
    @DisplayName("activarLocal_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void activarLocal_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        Usuario otroDueno = Usuario.builder().id(3L).email("otro@test.com").rol(Role.OWNER).build();

        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(AccessDeniedException.class,
                () -> dispositivoCajaService.activarLocal(10L, otroDueno.getEmail(), null, httpServletResponse));
        verify(dispositivoCajaRepository, never()).save(any());
    }

    @Test
    @DisplayName("revocar_Fallo_DispositivoNoEncontrado")
    void revocar_Fallo_DispositivoNoEncontrado() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(dispositivoCajaRepository.findByIdAndEstablecimientoId(999L, 10L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> dispositivoCajaService.revocar(10L, 999L, dueno.getEmail()));
    }
}
