package com.matiasmeira.sacaladelangulo.core.idempotencia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyFilter - Tests de idempotencia por header Idempotency-Key")
class IdempotencyFilterTest {

    @Mock
    private SolicitudIdempotenteRepository solicitudIdempotenteRepository;

    @Mock
    private FilterChain filterChain;

    private IdempotencyFilter idempotencyFilter;

    @BeforeEach
    void setUp() {
        idempotencyFilter = new IdempotencyFilter(solicitudIdempotenteRepository);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("jugador@test.com", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("doFilter_SinHeader_PasaDirectoSinConsultarElRepositorio")
    void doFilter_SinHeader_PasaDirectoSinConsultarElRepositorio() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        MockHttpServletResponse response = new MockHttpServletResponse();

        idempotencyFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(solicitudIdempotenteRepository);
    }

    @Test
    @DisplayName("doFilter_RutaNoProtegida_PasaDirectoAunConHeader")
    void doFilter_RutaNoProtegida_PasaDirectoAunConHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/otra-ruta");
        request.addHeader("Idempotency-Key", "clave-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        idempotencyFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(solicitudIdempotenteRepository);
    }

    @Test
    @DisplayName("doFilter_SinUsuarioAutenticado_PasaDirecto")
    void doFilter_SinUsuarioAutenticado_PasaDirecto() throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        idempotencyFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(solicitudIdempotenteRepository);
    }

    @Test
    @DisplayName("doFilter_ClaveNueva_EjecutaElControladorYGuardaLaRespuesta")
    void doFilter_ClaveNueva_EjecutaElControladorYGuardaLaRespuesta() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.empty());
        when(solicitudIdempotenteRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        doAnswer(invocation -> {
            HttpServletResponse resp = invocation.getArgument(1);
            resp.setStatus(201);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"id\":1}");
            return null;
        }).when(filterChain).doFilter(any(), any());

        idempotencyFilter.doFilter(request, response, filterChain);

        ArgumentCaptor<SolicitudIdempotente> captor = ArgumentCaptor.forClass(SolicitudIdempotente.class);
        verify(solicitudIdempotenteRepository).save(captor.capture());
        assertEquals(201, captor.getValue().getStatusRespuesta());
        assertEquals("{\"id\":1}", captor.getValue().getCuerpoRespuesta());
        assertEquals(201, response.getStatus());
        assertEquals("{\"id\":1}", response.getContentAsString());
    }

    @Test
    @DisplayName("doFilter_ClaveExistenteYaCompletada_DevuelveLaRespuestaCacheadaSinLlamarAlControlador")
    void doFilter_ClaveExistenteYaCompletada_DevuelveLaRespuestaCacheadaSinLlamarAlControlador() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SolicitudIdempotente existente = SolicitudIdempotente.builder()
                .clave("clave-abc")
                .usuarioEmail("jugador@test.com")
                .statusRespuesta(201)
                .contentTypeRespuesta("application/json")
                .cuerpoRespuesta("{\"id\":1}")
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.of(existente));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(201, response.getStatus());
        assertEquals("{\"id\":1}", response.getContentAsString());
        assertEquals("true", response.getHeader("Idempotency-Replayed"));
    }

    @Test
    @DisplayName("doFilter_ClaveExistenteEnProceso_Responde409SinLlamarAlControlador")
    void doFilter_ClaveExistenteEnProceso_Responde409SinLlamarAlControlador() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SolicitudIdempotente enProceso = SolicitudIdempotente.builder()
                .clave("clave-abc")
                .usuarioEmail("jugador@test.com")
                .statusRespuesta(null)
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.of(enProceso));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("doFilter_CarreraDeInsercionPerdida_Responde409")
    void doFilter_CarreraDeInsercionPerdida_Responde409() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.empty());
        when(solicitudIdempotenteRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("doFilter_ErrorDelServidor_NoCacheaLaRespuestaYEliminaLaSolicitud")
    void doFilter_ErrorDelServidor_NoCacheaLaRespuestaYEliminaLaSolicitud() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.empty());
        when(solicitudIdempotenteRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        doAnswer(invocation -> {
            HttpServletResponse resp = invocation.getArgument(1);
            resp.setStatus(500);
            return null;
        }).when(filterChain).doFilter(any(), any());

        idempotencyFilter.doFilter(request, response, filterChain);

        verify(solicitudIdempotenteRepository).delete(any(SolicitudIdempotente.class));
        verify(solicitudIdempotenteRepository, never()).save(any());
        assertEquals(500, response.getStatus());
    }
}
