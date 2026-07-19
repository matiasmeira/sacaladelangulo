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
 *
 * La IP se toma de {@code request.getRemoteAddr()}, no de headers como
 * X-Forwarded-For: el despliegue actual es una única instancia sin proxy/balanceador
 * delante, así que confiar en un header que el propio cliente puede enviar arbitrario
 * permitiría bypassear el límite rotándolo en cada request. Si en el futuro se agrega
 * un proxy de confianza delante (que sobreescriba el header en vez de anexarlo), hay que
 * volver a incorporar la lectura de X-Forwarded-For, pero solo confiando en él cuando la
 * request efectivamente viene de ese proxy conocido.
 */
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Público (no private): RutasProtegidasCoincidenConControllersTest, en otro paquete,
     * verifica que cada ruta hardcodeada acá siga existiendo como endpoint POST real, en
     * vez de asumirlo (ver M26 en la auditoría).
     */
    public static final Map<String, Limite> LIMITES_POR_RUTA = Map.of(
            "/api/v1/auth/login", new Limite(15, Duration.ofMinutes(5).toMillis()),
            "/api/v1/auth/empleados/login", new Limite(30, Duration.ofMinutes(5).toMillis()),
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

        String ip = request.getRemoteAddr();
        String clave = "ip:" + request.getRequestURI() + ":" + ip;
        if (!rateLimiterService.tryConsume(clave, limite.capacidad(), limite.ventanaMillis())) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private record Limite(int capacidad, long ventanaMillis) {
    }
}
