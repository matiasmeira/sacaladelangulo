package com.matiasmeira.sacaladelangulo.core.trazabilidad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pone un identificador único por request en el MDC, para poder agrupar todas las líneas
 * de log de una misma operación.
 *
 * <p>El formato ECS de producción ({@code logging.structured.format.console=ecs})
 * serializa el MDC por su cuenta, así que el traceId aparece como campo propio del JSON
 * sin necesidad de un logback-spring.xml ni de logstash-logback-encoder. Verificado
 * levantando el contexto con ese formato; la línea de salida es literalmente:
 *
 * <pre>
 * {"@timestamp":"...","log":{"level":"INFO",...},"message":"...","traceId":"trace-de-prueba-42","ecs":{"version":"8.11"}}
 * </pre>
 *
 * No quedó como test automatizado a propósito: el LoggingSystem de Spring Boot se
 * inicializa una vez por JVM y no se reconfigura entre contextos cacheados, así que un
 * test que afirme sobre el formato pasa aislado y falla dentro de la suite según el orden
 * — flaky, que es peor que no tenerlo.
 *
 * <p><b>Por qué un Filter y no un HandlerInterceptor.</b> Un interceptor corre DENTRO del
 * DispatcherServlet, así que solo ve las requests que llegan a un controller. Se perdería
 * justamente lo que más se necesita trazar: los 401/403 que resuelven
 * RestAuthenticationEntryPoint/RestAccessDeniedHandler, los 429 que RateLimitFilter
 * escribe por su cuenta, y los replays que corta IdempotencyFilter — todos ellos terminan
 * la request antes del despacho (mismo razonamiento que documenta RateLimitFilter sobre
 * por qué sus excepciones no pasan por GlobalExceptionHandler). Registrado con
 * HIGHEST_PRECEDENCE, este filtro envuelve incluso a la cadena de Spring Security.
 *
 * <p>El header entrante se acepta para poder correlacionar con un sistema de arriba, pero
 * se valida antes de usarlo: es un valor controlado por el cliente que va a parar a los
 * logs, y sin acotarlo se podría inyectar contenido arbitrario (en el patrón de texto de
 * desarrollo, un salto de línea alcanza para falsificar una entrada de log) o mandar una
 * cadena de megabytes que se repite en cada línea.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int LARGO_MAXIMO = 64;
    private static final Pattern FORMATO_ACEPTADO = Pattern.compile("[A-Za-z0-9._-]+");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolverTraceId(request.getHeader(TRACE_ID_HEADER));

        MDC.put(TRACE_ID_MDC_KEY, traceId);
        // Se setea antes de la cadena y no después: cualquier eslabón puede terminar la
        // request por su cuenta (401, 429, replay de idempotencia), y en esos casos el
        // header tiene que viajar igual para que el usuario pueda reportar el código.
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Solo esta clave, no MDC.clear(): el hilo viene de un pool y se reutiliza,
            // así que limpiar todo borraría lo que cualquier otro componente haya puesto.
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolverTraceId(String headerEntrante) {
        if (headerEntrante == null || headerEntrante.isBlank()
                || headerEntrante.length() > LARGO_MAXIMO
                || !FORMATO_ACEPTADO.matcher(headerEntrante).matches()) {
            return UUID.randomUUID().toString();
        }
        return headerEntrante;
    }
}
