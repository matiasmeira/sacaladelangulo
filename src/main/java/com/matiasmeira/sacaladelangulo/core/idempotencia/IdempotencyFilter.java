package com.matiasmeira.sacaladelangulo.core.idempotencia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Implementa idempotencia por header "Idempotency-Key" para operaciones que
 * crean un efecto de negocio (reservas, ventas): si el cliente reintenta la
 * misma solicitud (ej. timeout de red) con la misma clave, se devuelve la
 * respuesta ya generada en vez de repetir el efecto (doble reserva, doble
 * venta con doble descuento de stock, etc.).
 * <p>
 * Es opt-in: si el cliente no manda el header, el filtro no interviene y el
 * comportamiento es el de siempre. Solo aplica a las rutas configuradas.
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final String HEADER_CLAVE = "Idempotency-Key";
    private static final Set<String> RUTAS_PROTEGIDAS = Set.of(
            "/api/v1/reservas",
            "/api/v1/reservas/manual",
            "/api/v1/reservas/semanal",
            "/api/v1/buffet/ventas"
    );

    private final SolicitudIdempotenteRepository solicitudIdempotenteRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!aplicaAEstaRuta(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String clave = request.getHeader(HEADER_CLAVE);
        if (clave == null || clave.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String usuarioEmail = obtenerUsuarioAutenticado();
        if (usuarioEmail == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var existente = solicitudIdempotenteRepository.findByClaveAndUsuarioEmail(clave, usuarioEmail);
        if (existente.isPresent()) {
            atenderSolicitudExistente(existente.get(), response);
            return;
        }

        SolicitudIdempotente solicitud = SolicitudIdempotente.builder()
                .clave(clave)
                .usuarioEmail(usuarioEmail)
                .metodoHttp(request.getMethod())
                .path(request.getRequestURI())
                .fechaCreacion(LocalDateTime.now())
                .build();
        try {
            solicitudIdempotenteRepository.saveAndFlush(solicitud);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Carrera de idempotencia perdida para la clave: {}", clave);
            responderConflicto(response);
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            completarSolicitud(solicitud, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean aplicaAEstaRuta(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && RUTAS_PROTEGIDAS.contains(request.getRequestURI());
    }

    private String obtenerUsuarioAutenticado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        return authentication.getName();
    }

    private void atenderSolicitudExistente(SolicitudIdempotente solicitud, HttpServletResponse response) throws IOException {
        if (solicitud.getStatusRespuesta() == null) {
            responderConflicto(response);
            return;
        }
        response.setStatus(solicitud.getStatusRespuesta());
        if (solicitud.getContentTypeRespuesta() != null) {
            response.setContentType(solicitud.getContentTypeRespuesta());
        }
        response.setHeader("Idempotency-Replayed", "true");
        if (solicitud.getCuerpoRespuesta() != null) {
            response.getWriter().write(solicitud.getCuerpoRespuesta());
        }
    }

    private void responderConflicto(HttpServletResponse response) throws IOException {
        response.setStatus(409);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Ya existe una solicitud en curso con la misma clave de idempotencia.\"}");
    }

    private void completarSolicitud(SolicitudIdempotente solicitud, ContentCachingResponseWrapper wrappedResponse) {
        int status = wrappedResponse.getStatus();
        if (status >= 500) {
            // Error transitorio del servidor: no se cachea, para permitir que un reintento
            // real vuelva a intentar la operación en vez de repetir el error para siempre.
            solicitudIdempotenteRepository.delete(solicitud);
            return;
        }
        String cuerpo = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
        solicitud.setStatusRespuesta(status);
        solicitud.setContentTypeRespuesta(wrappedResponse.getContentType());
        solicitud.setCuerpoRespuesta(cuerpo);
        solicitud.setFechaCompletado(LocalDateTime.now());
        solicitudIdempotenteRepository.save(solicitud);
    }
}
