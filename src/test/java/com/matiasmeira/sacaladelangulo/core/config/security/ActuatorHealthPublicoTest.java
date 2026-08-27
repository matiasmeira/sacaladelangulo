package com.matiasmeira.sacaladelangulo.core.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fija que el health check quede alcanzable SIN credenciales, y que abrirlo no haya
 * abierto el resto de /actuator.
 *
 * El health check de Railway/Render/Fly sondea por HTTP sin credenciales: mientras
 * /actuator/health cayó bajo anyRequest().authenticated() devolvía 401, y la plataforma
 * daba la instancia por caída aunque estuviera sana (ver READINESS.md). Es una regla de
 * seguridad que sólo se puede verificar de punta a punta, así que va con un contenedor
 * servlet real y TestRestTemplate (que ante 4xx devuelve el status en vez de arrojar),
 * mismo criterio que RateLimitFilterIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-actuator-health;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false",
        // Mismo par que fija application-prod.properties: sin esto no existirían
        // /actuator/health/liveness ni /readiness, que son las rutas que sondean las probes.
        "management.endpoint.health.probes.enabled=true"
})
@DisplayName("SecurityConfig - /actuator/health accesible sin autenticacion")
class ActuatorHealthPublicoTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("get_actuatorHealth_sinCredencialesDevuelve200YStatusUp")
    void get_actuatorHealth_sinCredencialesDevuelve200YStatusUp() {
        ResponseEntity<String> respuesta = restTemplate.getForEntity("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertNotNull(respuesta.getBody());
        assertTrue(respuesta.getBody().contains("\"status\":\"UP\""),
                "El health check deberia reportar UP, pero devolvio: " + respuesta.getBody());
    }

    @Test
    @DisplayName("get_actuatorHealthLiveness_sinCredencialesDevuelve200")
    void get_actuatorHealthLiveness_sinCredencialesDevuelve200() {
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode());
    }

    @Test
    @DisplayName("get_actuatorHealthReadiness_sinCredencialesDevuelve200")
    void get_actuatorHealthReadiness_sinCredencialesDevuelve200() {
        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode());
    }

    /**
     * La contracara del cambio: se abrió health, NO /actuator entero. Si alguien agrega
     * management.endpoints.web.exposure.include=* o afloja el matcher a /actuator/**,
     * este test lo frena.
     */
    @Test
    @DisplayName("get_actuatorEnv_sinCredencialesNoDevuelve200")
    void get_actuatorEnv_sinCredencialesNoDevuelve200() {
        HttpStatus status = (HttpStatus) restTemplate.getForEntity("/actuator/env", String.class).getStatusCode();

        assertTrue(status != HttpStatus.OK,
                "/actuator/env no deberia ser accesible sin credenciales, pero devolvio 200");
    }

    /**
     * El health check no debe filtrar el desglose por componente (estado de la base,
     * espacio en disco): show-details=never en la config base lo garantiza, y este test
     * lo fija para que abrir la ruta no se convierta en filtrar detalle interno.
     */
    @Test
    @DisplayName("get_actuatorHealth_noExponeDetallePorComponente")
    void get_actuatorHealth_noExponeDetallePorComponente() {
        String cuerpo = restTemplate.getForEntity("/actuator/health", String.class).getBody();

        assertNotNull(cuerpo);
        assertTrue(!cuerpo.contains("\"components\"") && !cuerpo.contains("\"db\""),
                "El health check no deberia exponer componentes, pero devolvio: " + cuerpo);
    }
}
