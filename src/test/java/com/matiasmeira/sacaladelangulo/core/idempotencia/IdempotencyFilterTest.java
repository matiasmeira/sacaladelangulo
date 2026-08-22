package com.matiasmeira.sacaladelangulo.core.idempotencia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    @DisplayName("doFilter_ClaveDemasiadoLarga_Responde400SinConsultarElRepositorio")
    void doFilter_ClaveDemasiadoLarga_Responde400SinConsultarElRepositorio() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "x".repeat(256));
        MockHttpServletResponse response = new MockHttpServletResponse();

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        verifyNoInteractions(solicitudIdempotenteRepository);
        assertEquals(400, response.getStatus());
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
                .bodyHash(IdempotencyFilter.calcularHash(new byte[0]))
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
    @DisplayName("doFilter_ClaveExistenteConPayloadDistinto_Responde422SinLlamarAlControlador")
    void doFilter_ClaveExistenteConPayloadDistinto_Responde422SinLlamarAlControlador() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        request.setContent("{\"canchaId\":2}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        SolicitudIdempotente existente = SolicitudIdempotente.builder()
                .clave("clave-abc")
                .usuarioEmail("jugador@test.com")
                .statusRespuesta(201)
                .contentTypeRespuesta("application/json")
                .cuerpoRespuesta("{\"id\":1}")
                .bodyHash(IdempotencyFilter.calcularHash("{\"canchaId\":1}".getBytes()))
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.of(existente));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(422, response.getStatus());
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
                .bodyHash(IdempotencyFilter.calcularHash(new byte[0]))
                .fechaCreacion(java.time.LocalDateTime.now())
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.of(enProceso));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("doFilter_ClaveExistenteAbandonada_LaBorraYEjecutaElControladorComoSolicitudNueva")
    void doFilter_ClaveExistenteAbandonada_LaBorraYEjecutaElControladorComoSolicitudNueva() throws Exception {
        // El proceso murió entre guardar la solicitud y completarla: statusRespuesta sigue
        // null, pero ya pasó la ventana corta de "en curso" (ver M21).
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reservas");
        request.addHeader("Idempotency-Key", "clave-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        SolicitudIdempotente abandonada = SolicitudIdempotente.builder()
                .clave("clave-abc")
                .usuarioEmail("jugador@test.com")
                .statusRespuesta(null)
                .bodyHash(IdempotencyFilter.calcularHash(new byte[0]))
                .fechaCreacion(java.time.LocalDateTime.now().minusMinutes(10))
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-abc", "jugador@test.com"))
                .thenReturn(Optional.of(abandonada));
        when(solicitudIdempotenteRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        doAnswer(invocation -> {
            HttpServletResponse resp = invocation.getArgument(1);
            resp.setStatus(201);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"id\":2}");
            return null;
        }).when(filterChain).doFilter(any(), any());

        idempotencyFilter.doFilter(request, response, filterChain);

        verify(solicitudIdempotenteRepository).delete(abandonada);
        verify(filterChain).doFilter(any(), any());
        assertEquals(201, response.getStatus());
        assertEquals("{\"id\":2}", response.getContentAsString());
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

    /**
     * En multipart el filtro no puede drenar el input stream: Tomcat parsea las partes
     * desde ese mismo stream recién después de la cadena de filtros, y
     * CachedBodyHttpServletRequest sólo overridea getInputStream(), no getParts(), así que
     * el controller recibiría el archivo vacío. Este test maneja el filtro directo y no por
     * MockMvc a propósito: MockMultipartFile registra las partes aparte, en el mapa de
     * MockMultipartHttpServletRequest, y nunca serializa un cuerpo multipart, así que por
     * MockMvc un filtro que vacía la subida y uno que no la tocan dan el mismo verde.
     */
    @Test
    @DisplayName("doFilter_MultipartEnRutaProtegida_NoConsumeElCuerpoYPasaElRequestOriginal")
    void doFilter_MultipartEnRutaProtegida_NoConsumeElCuerpoYPasaElRequestOriginal() throws Exception {
        byte[] cuerpo = ("------x\r\n"
                + "Content-Disposition: form-data; name=\"archivo\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "\r\n"
                + "bytes-de-la-foto\r\n"
                + "------x--\r\n").getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/establecimientos/7/fotos");
        request.addHeader("Idempotency-Key", "clave-foto");
        request.setContentType("multipart/form-data; boundary=----x");
        request.setContent(cuerpo);
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-foto", "jugador@test.com"))
                .thenReturn(Optional.empty());
        when(solicitudIdempotenteRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        idempotencyFilter.doFilter(request, response, filterChain);

        // Ancla explícita de que el filtro se activó en esta ruta: sin esto, vaciar
        // PATRONES_PROTEGIDOS haría pasar el request de largo y las dos aserciones de abajo
        // seguirían verdes, porque un request original sin leer es justo lo que produce un
        // pass-through. El test quedaría siendo una tautología.
        verify(solicitudIdempotenteRepository).saveAndFlush(any());

        ArgumentCaptor<ServletRequest> captor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(filterChain).doFilter(captor.capture(), any());
        assertArrayEquals(cuerpo, request.getInputStream().readAllBytes(),
                "El filtro consumió el cuerpo multipart: al controller le llegaría el archivo vacío");
        assertSame(request, captor.getValue(),
                "En multipart la cadena tiene que recibir el request original, no el envoltorio:"
                        + " getParts() de Tomcat delega al original y ahí es donde se parsea el archivo");
    }

    /**
     * La contracara del test de arriba: cuando el filtro responde de cache y NO llama a la
     * cadena, el cuerpo multipart sí tiene que quedar drenado. Si se devuelve la respuesta
     * dejando sin leer el cuerpo, Tomcat se traga hasta maxSwallowSize (2 MB por defecto) y
     * corta la conexión, así que reintentar una foto de más de 2 MB — el caso para el que
     * existe Idempotency-Key — daría connection reset en vez de la respuesta cacheada.
     */
    @Test
    @DisplayName("doFilter_MultipartRepetidoDesdeCache_DrenaElCuerpoYNoLlamaALaCadena")
    void doFilter_MultipartRepetidoDesdeCache_DrenaElCuerpoYNoLlamaALaCadena() throws Exception {
        byte[] cuerpo = ("------x\r\n"
                + "Content-Disposition: form-data; name=\"archivo\"; filename=\"foto.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n"
                + "\r\n"
                + "bytes-de-la-foto\r\n"
                + "------x--\r\n").getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/establecimientos/7/fotos");
        request.addHeader("Idempotency-Key", "clave-foto");
        request.setContentType("multipart/form-data; boundary=----x");
        request.setContent(cuerpo);
        MockHttpServletResponse response = new MockHttpServletResponse();

        SolicitudIdempotente existente = SolicitudIdempotente.builder()
                .clave("clave-foto")
                .usuarioEmail("jugador@test.com")
                .statusRespuesta(201)
                .contentTypeRespuesta("application/json")
                .cuerpoRespuesta("{\"fileId\":\"file_idem\"}")
                .bodyHash(IdempotencyFilter.calcularHash(new byte[0]))
                .build();
        when(solicitudIdempotenteRepository.findByClaveAndUsuarioEmail("clave-foto", "jugador@test.com"))
                .thenReturn(Optional.of(existente));

        idempotencyFilter.doFilter(request, response, filterChain);

        verifyNoInteractions(filterChain);
        assertEquals(201, response.getStatus());
        assertEquals("{\"fileId\":\"file_idem\"}", response.getContentAsString());
        assertEquals(0, request.getInputStream().readAllBytes().length,
                "El cuerpo multipart quedó sin drenar en el replay: Tomcat cortaría la conexión"
                        + " en vez de devolver la respuesta cacheada");
    }
}
