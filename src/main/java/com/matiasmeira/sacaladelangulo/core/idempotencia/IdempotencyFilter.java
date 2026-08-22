package com.matiasmeira.sacaladelangulo.core.idempotencia;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
    /**
     * Público (no private): RutasProtegidasCoincidenConControllersTest, en otro paquete,
     * verifica que cada ruta acá siga existiendo como endpoint POST real, para detectar en
     * el test suite (no en producción) si un refactor de rutas desincroniza este set de
     * los controllers reales (ver M26 en la auditoría).
     */
    public static final Set<String> RUTAS_PROTEGIDAS = Set.of(
            "/api/v1/reservas",
            "/api/v1/reservas/manual",
            "/api/v1/reservas/semanal",
            "/api/v1/buffet/ventas"
    );
    /**
     * Rutas protegidas que llevan un id adentro, y por eso NO se pueden matchear por
     * igualdad de string como RUTAS_PROTEGIDAS. Se guardan como patrón y se matchean con
     * PathPattern. Separado y no fusionado con el set de arriba a propósito: el matcheo
     * exacto de las 4 rutas de reserva/venta es más barato y ya está probado, y no se
     * cambia su comportamiento por agregar esto.
     * <p>
     * Público por el mismo motivo que RUTAS_PROTEGIDAS: lo verifica
     * RutasProtegidasCoincidenConControllersTest.
     */
    public static final Set<String> PATRONES_PROTEGIDOS = Set.of(
            "/api/v1/establecimientos/{id}/fotos"
    );

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final List<PathPattern> PATRONES_COMPILADOS = PATRONES_PROTEGIDOS.stream()
            .map(PARSER::parse)
            .toList();
    /**
     * Ventana corta para distinguir "en curso" de "abandonada" (el proceso murió entre
     * guardar la solicitud y completarla — ver M21 en la auditoría), independiente de la
     * retención de 24h de IdempotencyCleanupService: sin esto, una solicitud interrumpida
     * bloquea cualquier reintento legítimo del cliente hasta que se limpie por completo.
     */
    private static final long TIMEOUT_EN_CURSO_MINUTOS = 3;
    /**
     * Debe coincidir con SolicitudIdempotente.clave (length = 255): sin esta validación
     * temprana, una clave más larga que la columna llega hasta el INSERT y revienta con
     * DataIntegrityViolationException, indistinguible ahí de una carrera de idempotencia
     * genuina (el catch de más abajo respondería 409 con un mensaje engañoso, ver B15).
     */
    private static final int LONGITUD_MAXIMA_CLAVE = 255;

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
        if (clave.length() > LONGITUD_MAXIMA_CLAVE) {
            responderClaveInvalida(response);
            return;
        }

        String usuarioEmail = obtenerUsuarioAutenticado();
        if (usuarioEmail == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // En multipart NO se toca el input stream. CachedBodyHttpServletRequest sólo
        // overridea getInputStream()/getReader(); getParts() delega al request original de
        // Tomcat, así que drenar el stream acá le deja el archivo vacío al controller.
        // El precio es perder la detección de "misma clave con otro payload" (el 422) para
        // multipart: la idempotencia en sí, que es no repetir el efecto, sigue intacta.
        boolean esMultipart = request.getContentType() != null
                && request.getContentType().toLowerCase().startsWith("multipart/");
        byte[] cuerpoBytes = esMultipart ? new byte[0] : request.getInputStream().readAllBytes();
        HttpServletRequest requestConCuerpoCacheado = esMultipart
                ? request
                : new CachedBodyHttpServletRequest(request, cuerpoBytes);
        String hashCuerpo = calcularHash(cuerpoBytes);

        var existente = solicitudIdempotenteRepository.findByClaveAndUsuarioEmail(clave, usuarioEmail);
        if (existente.isPresent() && estaAbandonada(existente.get())) {
            log.warn("Solicitud de idempotencia abandonada (sin completar tras {} min). Clave: {}", TIMEOUT_EN_CURSO_MINUTOS, clave);
            solicitudIdempotenteRepository.delete(existente.get());
            existente = Optional.empty();
        }
        if (existente.isPresent()) {
            if (!hashCuerpo.equals(existente.get().getBodyHash())) {
                log.warn("Idempotency-Key reutilizada con un payload distinto. Clave: {}", clave);
                responderClaveReutilizadaConOtroPayload(response);
                return;
            }
            atenderSolicitudExistente(existente.get(), response);
            return;
        }

        SolicitudIdempotente solicitud = SolicitudIdempotente.builder()
                .clave(clave)
                .usuarioEmail(usuarioEmail)
                .metodoHttp(request.getMethod())
                .path(request.getRequestURI())
                .bodyHash(hashCuerpo)
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
            filterChain.doFilter(requestConCuerpoCacheado, wrappedResponse);
        } finally {
            completarSolicitud(solicitud, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private boolean aplicaAEstaRuta(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (RUTAS_PROTEGIDAS.contains(uri)) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(uri);
        return PATRONES_COMPILADOS.stream().anyMatch(patron -> patron.matches(path));
    }

    private String obtenerUsuarioAutenticado() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return null;
        }
        return authentication.getName();
    }

    private boolean estaAbandonada(SolicitudIdempotente solicitud) {
        return solicitud.getStatusRespuesta() == null
                && solicitud.getFechaCreacion().isBefore(LocalDateTime.now().minusMinutes(TIMEOUT_EN_CURSO_MINUTOS));
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

    private void responderClaveInvalida(HttpServletResponse response) throws IOException {
        response.setStatus(400);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"La clave de idempotencia no puede superar los " + LONGITUD_MAXIMA_CLAVE + " caracteres.\"}");
    }

    private void responderClaveReutilizadaConOtroPayload(HttpServletResponse response) throws IOException {
        response.setStatus(422);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"La clave de idempotencia ya se usó con un cuerpo de solicitud distinto.\"}");
    }

    /**
     * Hash del cuerpo de la request, para detectar que un cliente reutilice la misma
     * Idempotency-Key con un payload distinto (bug de cliente o key mal generada): sin
     * esto, el filtro devolvería silenciosamente la respuesta de la primera operación
     * como si correspondiera a la segunda, aunque sean solicitudes de negocio distintas.
     */
    static String calcularHash(byte[] cuerpoBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(cuerpoBytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    /**
     * Envoltorio que permite leer el cuerpo de la request más de una vez: el filtro lo lee
     * primero para calcular el hash de idempotencia, y el controlador necesita poder
     * volver a leerlo después (deserialización de @RequestBody) sin que el stream original
     * ya esté consumido.
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
        private final byte[] cuerpo;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cuerpo) {
            super(request);
            this.cuerpo = cuerpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cuerpo);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return byteArrayInputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }

                @Override
                public int read() {
                    return byteArrayInputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
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
