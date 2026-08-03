package com.matiasmeira.sacaladelangulo.core;

import com.matiasmeira.sacaladelangulo.core.idempotencia.IdempotencyFilter;
import com.matiasmeira.sacaladelangulo.core.ratelimit.RateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IdempotencyFilter y RateLimitFilter hardcodean rutas por match exacto de string, sin
 * ninguna dependencia de compilación hacia los controllers reales: un refactor de rutas
 * los desincroniza en silencio, sin error de compilación ni de test (ver M26 en la
 * auditoría). Este test detecta esa desincronización acá, verificando que cada ruta
 * hardcodeada siga resolviendo a un @PostMapping real.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-m26;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        // Ver comentario equivalente en SacaladelanguloApplicationTests: evita que
        // FlywayAutoConfiguration corra las migraciones de Postgres contra este H2.
        "spring.flyway.enabled=false"
})
@DisplayName("Rutas hardcodeadas en IdempotencyFilter/RateLimitFilter siguen mapeadas a un controller real (ver M26)")
class RutasProtegidasCoincidenConControllersTest {

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    @DisplayName("rutasDeIdempotencyFilter_MapeanAUnPostMappingReal")
    void rutasDeIdempotencyFilter_MapeanAUnPostMappingReal() {
        IdempotencyFilter.RUTAS_PROTEGIDAS.forEach(this::assertRutaTienePostMapping);
    }

    @Test
    @DisplayName("rutasDeRateLimitFilter_MapeanAUnPostMappingReal")
    void rutasDeRateLimitFilter_MapeanAUnPostMappingReal() {
        RateLimitFilter.LIMITES_POR_RUTA.keySet().forEach(this::assertRutaTienePostMapping);
    }

    private void assertRutaTienePostMapping(String ruta) {
        boolean existe = requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> esPostMappingDeEsaRuta(info, ruta));
        assertTrue(existe, "No se encontró ningún @PostMapping registrado para la ruta " + ruta
                + " (el filtro y los controllers quedaron desincronizados)");
    }

    private boolean esPostMappingDeEsaRuta(RequestMappingInfo info, String ruta) {
        boolean esPost = info.getMethodsCondition().getMethods().contains(RequestMethod.POST);
        boolean coincideRuta = info.getPathPatternsCondition() != null
                && info.getPathPatternsCondition().getPatterns().stream()
                        .anyMatch(patron -> patron.getPatternString().equals(ruta));
        return esPost && coincideRuta;
    }
}
