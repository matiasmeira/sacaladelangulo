package com.matiasmeira.sacaladelangulo.core.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifica el status HTTP que efectivamente recibe un cliente real cuando se agota el
 * límite de /api/v1/auth/register/owner (5 cada 10 minutos, ver
 * RateLimitFilter.LIMITES_POR_RUTA).
 *
 * MockMvc no sirve para esto: RateLimitFilter corre como servlet filter dentro de la
 * cadena de Spring Security, ANTES del DispatcherServlet. Si lanza una excepción en vez
 * de escribir la respuesta él mismo, esa excepción sale de mockMvc.perform(...) como una
 * excepción arrojada en el test, no como una respuesta HTTP -- así que MockMvc no puede
 * distinguir "el filtro devuelve 429" de "el filtro revienta y el contenedor real
 * devolvería 500". Por eso este test usa un contenedor servlet real (RANDOM_PORT) y
 * TestRestTemplate, que no arroja ante 4xx/5xx sino que devuelve el status tal cual lo
 * vería un cliente real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-ratelimit-register-owner;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("RateLimitFilter - status real que recibe el cliente al agotar el limite")
class RateLimitFilterIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("post_registerOwner_sextaSolicitudDevuelve429")
    void post_registerOwner_sextaSolicitudDevuelve429() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> body = new HttpEntity<>("{}", headers);

        ResponseEntity<String> ultima = null;
        for (int i = 1; i <= 6; i++) {
            ultima = restTemplate.postForEntity("/api/v1/auth/register/owner", body, String.class);
            System.out.println("MEDICION intento " + i + " -> status=" + ultima.getStatusCode()
                    + " body=" + ultima.getBody());
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ultima.getStatusCode());
        assertEquals("{\"error\":\"Demasiados intentos desde esta IP. Intente nuevamente en unos minutos.\"}",
                ultima.getBody());
    }
}
