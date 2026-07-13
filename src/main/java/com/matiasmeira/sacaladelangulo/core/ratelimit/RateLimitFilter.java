package com.matiasmeira.sacaladelangulo.core.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Limita, por IP, los intentos a los endpoints públicos de autenticación
 * (login, login de empleado y registro), para mitigar fuerza bruta y spam de
 * cuentas. Es una defensa adicional a los límites por identidad de negocio
 * (ver AuthService), que protegen una cuenta puntual sin importar desde qué IP
 * se la ataque.
 */
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Map<String, Limite> LIMITES_POR_RUTA = Map.of(
            "/api/v1/auth/login", new Limite(15, Duration.ofMinutes(5).toMillis()),
            "/api/v1/auth/empleados/login", new Limite(30, Duration.ofMinutes(5).toMillis()),
            "/api/v1/auth/register/player", new Limite(5, Duration.ofMinutes(10).toMillis()),
            "/api/v1/auth/register/owner", new Limite(5, Duration.ofMinutes(10).toMillis())
    );

    private final RateLimiterService rateLimiterService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Limite limite = LIMITES_POR_RUTA.get(request.getRequestURI());
        if (limite == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = obtenerIpCliente(request);
        String clave = "ip:" + request.getRequestURI() + ":" + ip;
        if (!rateLimiterService.tryConsume(clave, limite.capacidad(), limite.ventanaMillis())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String obtenerIpCliente(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record Limite(int capacidad, long ventanaMillis) {
    }
}
