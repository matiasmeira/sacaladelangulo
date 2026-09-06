package com.matiasmeira.sacaladelangulo.core.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitFilter - Tests de límite por IP en endpoints de autenticación")
class RateLimitFilterTest {

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private FilterChain filterChain;

    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(rateLimiterService);
    }

    @Test
    @DisplayName("doFilter_RutaNoConfigurada_PasaDirectoSinConsultarElLimitador")
    void doFilter_RutaNoConfigurada_PasaDirectoSinConsultarElLimitador() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(rateLimiterService);
    }

    @Test
    @DisplayName("doFilter_RutaConfigurada_ConIntentosDisponibles_DejaPasar")
    void doFilter_RutaConfigurada_ConIntentosDisponibles_DejaPasar() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);

        rateLimitFilter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter_RutaConfigurada_SinIntentosDisponibles_Responde429YNoLlamaAlChain")
    void doFilter_RutaConfigurada_SinIntentosDisponibles_Responde429YNoLlamaAlChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(false);

        rateLimitFilter.doFilter(request, response, filterChain);

        assertEquals(429, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertEquals("{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}", response.getContentAsString());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter_RegistroIniciar_EstaLimitadoPorIp")
    void doFilter_RegistroIniciar_EstaLimitadoPorIp() throws Exception {
        // Cada llamada a este endpoint dispara un email REAL a la dirección que venga en el
        // body. El único tope que tenía era de 3 cada 15 min POR EMAIL (ver
        // RegistroVerificacionService.iniciarRegistro), que se esquiva rotando direcciones:
        // alcanzaba para usar el dominio de Canchear como relay de correo no solicitado hacia
        // una lista arbitraria, quemándole la reputación de envío.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/registro/iniciar");
        request.setRemoteAddr("10.0.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(false);

        rateLimitFilter.doFilter(request, response, filterChain);

        assertEquals(429, response.getStatus());
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("doFilter_IgnoraXForwardedFor_UsaSiempreElRemoteAddr")
    void doFilter_IgnoraXForwardedFor_UsaSiempreElRemoteAddr() throws Exception {
        // Sin proxy de confianza delante (despliegue de instancia única), el header
        // X-Forwarded-For lo puede mandar cualquier cliente: si se confiara en él,
        // alcanzaría con rotarlo en cada request para bypassear el límite por IP.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);

        rateLimitFilter.doFilter(request, response, filterChain);

        ArgumentCaptor<String> claveCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService).tryConsume(claveCaptor.capture(), anyInt(), anyLong());
        assertTrue(claveCaptor.getValue().contains("127.0.0.1"));
        assertTrue(!claveCaptor.getValue().contains("203.0.113.9"));
    }
}
