package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Red de seguridad (pendiente 17): todo endpoint de los @RestController de la aplicación tiene
 * que declarar {@code @PreAuthorize} (en el método o en la clase), salvo los de
 * {@link #SIN_PREAUTHORIZE}, cada uno con el motivo. Un endpoint nuevo sin anotación hace fallar
 * este test: la chain sólo exige "autenticado" (anyRequest().authenticated()), sin distinguir
 * roles, así que olvidarse la anotación deja el endpoint abierto a cualquier usuario logueado.
 */
@DisplayName("Cobertura de @PreAuthorize en los endpoints")
class CoberturaPreAuthorizeTest extends AbstractSecurityWebTest {

    private static final String PAQUETE_APP = "com.matiasmeira.sacaladelangulo";

    /**
     * Clave: "VERBO /ruta". Valor: por qué no lleva @PreAuthorize.
     * Agregar una entrada acá es una decisión de seguridad: el motivo tiene que explicar quién
     * puede llamar al endpoint y dónde se hace la validación real.
     */
    private static final Map<String, String> SIN_PREAUTHORIZE = Map.ofEntries(
            // --- Públicos por la chain: permitAll en SecurityConfig, sin sesión ---
            Map.entry("POST /api/v1/auth/login", "Público (permitAll /api/v1/auth/**): login; rate limit por IP."),
            Map.entry("POST /api/v1/auth/empleados/login", "Público (permitAll /api/v1/auth/**): login de mostrador por PIN; rate limit por IP."),
            Map.entry("POST /api/v1/auth/register/owner", "Público (permitAll /api/v1/auth/**): alta de dueños; rate limit por IP."),
            Map.entry("POST /api/v1/auth/register/player", "Público (permitAll /api/v1/auth/**): alta de jugadores (endpoint deprecado)."),
            Map.entry("POST /api/v1/auth/registro/iniciar", "Público (permitAll /api/v1/auth/**): paso 1 del registro de jugadores; rate limit por IP."),
            Map.entry("POST /api/v1/auth/registro/verificar-codigo", "Público (permitAll /api/v1/auth/**): paso 2 del registro de jugadores."),
            Map.entry("POST /api/v1/auth/registro/completar", "Público (permitAll /api/v1/auth/**): paso 3 del registro de jugadores."),
            Map.entry("GET /api/v1/auth/registro/verificar", "Público (permitAll /api/v1/auth/**): valida el token opaco del link de registro."),
            Map.entry("POST /api/v1/auth/password/recuperar", "Público (permitAll /api/v1/auth/**): pedido de recuperación de contraseña."),
            Map.entry("POST /api/v1/auth/password/reset", "Público (permitAll /api/v1/auth/**): reseteo con token opaco."),
            Map.entry("GET /api/v1/publico/complejos", "Público (permitAll GET /api/v1/publico/**): buscador de complejos."),
            Map.entry("GET /api/v1/publico/complejos/{slug}", "Público (permitAll GET /api/v1/publico/**): ficha del complejo."),
            Map.entry("GET /api/v1/publico/complejos/{slug}/disponibilidad", "Público (permitAll GET /api/v1/publico/**): grilla de disponibilidad."),
            Map.entry("GET /api/v1/establecimientos/{establecimientoId}/empleados/activos", "permitAll en la chain: la autorización real la hace DispositivoCajaGate (cookie de dispositivo) dentro del controller."),
            Map.entry("POST /api/v1/caja/emparejar", "permitAll en la chain: se autentica con el código de emparejamiento del body; rate limit por IP."),
            Map.entry("POST /api/v1/webhooks/resend", "permitAll en la chain: no hay sesión, el proveedor llama con una firma que valida el controller."),
            Map.entry("POST /api/v1/mails/baja", "permitAll en la chain: el token opaco del body identifica al usuario, sin sesión."),
            // --- Cuenta propia: cualquier rol autenticado opera sobre sí mismo, la identidad sale del token ---
            Map.entry("POST /api/v1/auth/logout", "Cuenta propia: authenticated() en la chain; invalida los tokens del usuario del token."),
            Map.entry("GET /api/v1/usuarios/me", "Cuenta propia: cualquier rol autenticado ve su perfil; el usuario sale del token."),
            Map.entry("DELETE /api/v1/usuarios/me", "Cuenta propia: cualquier rol autenticado da de baja su propia cuenta; el usuario sale del token."),
            Map.entry("POST /api/v1/usuarios/telefono/solicitar-codigo", "Cuenta propia: verificación del teléfono del usuario del token."),
            Map.entry("POST /api/v1/usuarios/telefono/verificar-codigo", "Cuenta propia: verificación del teléfono del usuario del token."),
            // --- Admin con el rol validado en el service: ver pendiente 44 ---
            Map.entry("DELETE /api/v1/admin/usuarios/{id}", "ADMIN en el service (UsuarioEliminacionService.eliminarComoAdmin); ver pendiente 44."),
            Map.entry("POST /api/v1/admin/mails/oferta", "ADMIN en el service (OfertaMarketingService.enviarOferta); ver pendiente 44.")
    );

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("todoEndpointDeclaraPreAuthorizeOEstaEnLaListaBlanca")
    void todoEndpointDeclaraPreAuthorizeOEstaEnLaListaBlanca() {
        Set<String> sinAnotacion = new TreeSet<>();
        Map<String, String> encontrados = new TreeMap<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entrada : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod metodo = entrada.getValue();
            Class<?> controller = metodo.getBeanType();
            if (!controller.getName().startsWith(PAQUETE_APP)
                    || !AnnotatedElementUtils.hasAnnotation(controller, RestController.class)) {
                continue;
            }
            boolean anotado = AnnotatedElementUtils.hasAnnotation(metodo.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class);
            for (String clave : claves(entrada.getKey())) {
                encontrados.put(clave, controller.getSimpleName() + "#" + metodo.getMethod().getName());
                if (!anotado) {
                    sinAnotacion.add(clave);
                }
            }
        }

        List<String> problemas = new ArrayList<>();
        for (String clave : sinAnotacion) {
            if (!SIN_PREAUTHORIZE.containsKey(clave)) {
                problemas.add("Endpoint sin @PreAuthorize: " + clave + " (" + encontrados.get(clave) + "). "
                        + "Agregale @PreAuthorize con el rol que corresponda; si de verdad es público o propio, "
                        + "sumalo a SIN_PREAUTHORIZE en CoberturaPreAuthorizeTest con el motivo.");
            }
        }
        for (String clave : SIN_PREAUTHORIZE.keySet()) {
            if (!encontrados.containsKey(clave)) {
                problemas.add("La lista blanca menciona un endpoint que ya no existe: " + clave + ". Sacalo de SIN_PREAUTHORIZE.");
            } else if (!sinAnotacion.contains(clave)) {
                problemas.add("La lista blanca menciona " + clave + " pero ya tiene @PreAuthorize. Sacalo de SIN_PREAUTHORIZE.");
            }
        }

        assertTrue(problemas.isEmpty(), () -> "\n" + String.join("\n", problemas));
    }

    private static List<String> claves(RequestMappingInfo info) {
        Set<String> rutas = info.getPathPatternsCondition() != null
                ? info.getPathPatternsCondition().getPatternValues() : Set.of();
        Set<String> verbos = new TreeSet<>();
        info.getMethodsCondition().getMethods().forEach(m -> verbos.add(m.name()));
        if (verbos.isEmpty()) {
            verbos.add("ANY");
        }
        List<String> claves = new ArrayList<>();
        for (String verbo : verbos) {
            for (String ruta : rutas) {
                claves.add(verbo + " " + ruta);
            }
        }
        return claves;
    }
}
