package com.matiasmeira.sacaladelangulo.core.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
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
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    /**
     * Público (no private): RutasProtegidasCoincidenConControllersTest, en otro paquete,
     * verifica que cada ruta hardcodeada acá siga existiendo como endpoint POST real, en
     * vez de asumirlo (ver M26 en la auditoría).
     */
    public static final Map<String, Limite> LIMITES_POR_RUTA = Map.of(
            "/api/v1/auth/login", new Limite(15, Duration.ofMinutes(5).toMillis()),
            "/api/v1/auth/empleados/login", new Limite(30, Duration.ofMinutes(5).toMillis()),
            "/api/v1/auth/register/owner", new Limite(5, Duration.ofMinutes(10).toMillis()),
            "/api/v1/caja/emparejar", new Limite(10, Duration.ofMinutes(5).toMillis())
    );

    private final RateLimiterService rateLimiterService;

    // Política específica para endpoints de mails (mucho más restrictiva)
    private static final int MAIL_CAPACITY = 5; // tokens máximos
    private static final long MAIL_VENTANA_MILLIS = Duration.ofMinutes(1).toMillis(); // ventana de refill

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();

        // 1) Política especial para endpoints de mails
        boolean isMailEndpoint = path.startsWith("/api/v1/mails") || path.startsWith("/api/v1/admin/mails");
        if (isMailEndpoint) {
            String ip = request.getRemoteAddr();
            // priorizar identidad autenticada para granularidad por usuario; caer a IP si no hay auth
            String userId = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails) {
                userId = ((UserDetails) auth.getPrincipal()).getUsername();
            }

            String clave = (userId != null) ? "mail:user:" + userId : "mail:ip:" + ip;
            TokenBucket bucket = rateLimiterService.getOrCreateBucket(clave, MAIL_CAPACITY, MAIL_VENTANA_MILLIS);
            if (!bucket.tryConsume()) {
                log.warn("Mail rate limit excedido para clave={}", clave);
                responder429(response, "Demasiadas solicitudes de envío de email. Intentá nuevamente más tarde.");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 2) Comportamiento existente para rutas listadas en LIMITES_POR_RUTA
        Limite limite = LIMITES_POR_RUTA.get(path);
        if (limite == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = request.getRemoteAddr();
        String clave = "ip:" + path + ":" + ip;
        if (!rateLimiterService.tryConsume(clave, limite.capacidad(), limite.ventanaMillis())) {
            responder429(response, "Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Escribe la respuesta de rechazo directamente sobre el response, en vez de lanzar
     * (ver medición en .superpowers/sdd/ratelimit-429-medicion.md): este filtro corre
     * dentro de la cadena de Spring Security, ANTES del DispatcherServlet, así que una
     * excepción acá no pasa por GlobalExceptionHandler (que es un @RestControllerAdvice,
     * atado al despacho del controller) ni por ExceptionTranslationFilter (que sólo
     * traduce AuthenticationException/AccessDeniedException). Sale cruda hacia el
     * contenedor, que la convierte en un 500 genérico en vez del 429 esperado.
     */
    private void responder429(HttpServletResponse response, String mensaje) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + mensaje + "\"}");
    }

    private record Limite(int capacidad, long ventanaMillis) {
    }
}
