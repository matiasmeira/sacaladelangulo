package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Barrido de 401 (pendiente 46): todo endpoint de los @RestController de la aplicación que NO es
 * público tiene que responder 401 {"error":"No autenticado"} cuando llega sin Authorization. Reemplaza
 * repetir el caso "sin token" en cada clase de autorización.
 *
 * <p>"Público" es lo que la lista blanca de {@link CoberturaPreAuthorizeTest#SIN_PREAUTHORIZE} declara
 * con permitAll en la chain (su motivo lo dice); no se duplica la lista acá. Las entradas de "cuenta
 * propia" o "admin en el service" de esa lista NO son públicas: la chain las exige autenticadas y este
 * barrido las incluye. Si alguien agrega un permitAll en SecurityConfig para un endpoint que la lista
 * no declara público, el request deja de dar 401 y este test falla.
 *
 * <p>Los path variables se completan con "1": el 401 lo corta la chain antes del controller. El único
 * filtro previo con límite que alcanza a endpoints no públicos es el de mails (RateLimitFilter, 5 por
 * minuto por IP sin sesión): el barrido pega una sola vez en /api/v1/admin/mails/oferta, y la base le da
 * a cada test su propia IP (ver AbstractSecurityWebTest), así que ese cupo no se comparte con otras clases.
 *
 * <p>Se manda siempre Idempotency-Key: IdempotencyFilter corre antes de la autorización y en los 4 POST
 * de clave obligatoria (reservas, reservas/manual, turnos-fijos, buffet/ventas) responde 400 si falta
 * el header, aun sin token. Con la clave puesta y sin usuario el filtro deja pasar sin guardar nada y
 * el que corta es la chain (401).
 */
@DisplayName("Barrido: sin token, todo endpoint no público devuelve 401")
class SinTokenBarridoTest extends AbstractSecurityWebTest {

    private static final String PAQUETE_APP = "com.matiasmeira.sacaladelangulo";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("todoEndpointNoPublicoSinTokenDevuelve401")
    void todoEndpointNoPublicoSinTokenDevuelve401() throws Exception {
        TreeSet<String> endpoints = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entrada : handlerMapping.getHandlerMethods().entrySet()) {
            Class<?> controller = entrada.getValue().getBeanType();
            if (!controller.getName().startsWith(PAQUETE_APP)
                    || !AnnotatedElementUtils.hasAnnotation(controller, RestController.class)) {
                continue;
            }
            endpoints.addAll(CoberturaPreAuthorizeTest.claves(entrada.getKey()));
        }

        List<String> problemas = new ArrayList<>();
        int probados = 0;
        for (String clave : endpoints) {
            if (esPublico(clave)) {
                continue;
            }
            String verbo = clave.substring(0, clave.indexOf(' '));
            String ruta = clave.substring(clave.indexOf(' ') + 1).replaceAll("\\{[^}]*}", "1");
            HttpMethod metodo = "ANY".equals(verbo) ? HttpMethod.GET : HttpMethod.valueOf(verbo);

            MockHttpServletResponse respuesta = mockMvc.perform(request(metodo, ruta)
                    .header("Idempotency-Key", "barrido-sin-token"))
                    .andReturn().getResponse();
            probados++;
            String cuerpo = respuesta.getContentAsString();
            if (respuesta.getStatus() != 401 || !cuerpo.contains("\"error\":\"No autenticado\"")) {
                problemas.add(clave + " -> " + respuesta.getStatus() + " " + cuerpo);
            }
        }

        assertTrue(probados > 0, "El barrido no encontró endpoints no públicos");
        assertTrue(problemas.isEmpty(), () -> "Endpoints no públicos que no exigen token:\n" + String.join("\n", problemas));
    }

    private static boolean esPublico(String clave) {
        String motivo = CoberturaPreAuthorizeTest.SIN_PREAUTHORIZE.get(clave);
        return motivo != null && motivo.contains("permitAll");
    }
}
