package com.matiasmeira.sacaladelangulo.core.trazabilidad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica el traceId de punta a punta, con un contenedor servlet real: el punto del
 * filtro es cubrir también las requests que terminan ANTES del DispatcherServlet (401 de
 * Spring Security, 429 de RateLimitFilter), y eso con MockMvc no se puede observar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-traceid;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("TraceIdFilter - trazabilidad por request")
class TraceIdFilterIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private String traceIdDe(ResponseEntity<String> respuesta) {
        return respuesta.getHeaders().getFirst(TraceIdFilter.TRACE_ID_HEADER);
    }

    private ResponseEntity<String> getConHeader(String ruta, String valorTraceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.TRACE_ID_HEADER, valorTraceId);
        return restTemplate.exchange(ruta, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("responseIncluyeTraceId_enUnaRutaPublica")
    void responseIncluyeTraceId_enUnaRutaPublica() {
        ResponseEntity<String> respuesta = restTemplate.getForEntity("/actuator/health", String.class);

        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertNotNull(traceIdDe(respuesta), "Toda respuesta deberia traer X-Trace-Id");
    }

    /**
     * El motivo de que esto sea un Filter y no un HandlerInterceptor: un 401 lo resuelve
     * Spring Security antes del DispatcherServlet, así que un interceptor nunca lo vería
     * y el incidente más común (alguien no puede entrar) quedaría sin traza.
     */
    @Test
    @DisplayName("responseIncluyeTraceId_aunqueLaRequestMuera_en401")
    void responseIncluyeTraceId_aunqueLaRequestMuera_en401() {
        ResponseEntity<String> respuesta = restTemplate.getForEntity("/api/v1/usuarios/me", String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, respuesta.getStatusCode());
        assertNotNull(traceIdDe(respuesta), "Un 401 tambien deberia ser trazable");
    }

    @Test
    @DisplayName("dosRequests_recibenTraceIdsDistintos")
    void dosRequests_recibenTraceIdsDistintos() {
        String primero = traceIdDe(restTemplate.getForEntity("/actuator/health", String.class));
        String segundo = traceIdDe(restTemplate.getForEntity("/actuator/health", String.class));

        assertNotNull(primero);
        assertNotNull(segundo);
        assertNotEquals(primero, segundo, "Cada request deberia tener su propio traceId");
    }

    @Test
    @DisplayName("headerEntranteValido_seReutilizaParaCorrelacionar")
    void headerEntranteValido_seReutilizaParaCorrelacionar() {
        String propio = "trace-de-otro-sistema-123";

        assertEquals(propio, traceIdDe(getConHeader("/actuator/health", propio)));
    }

    /**
     * El header lo controla el cliente y termina en los logs: si se aceptara tal cual, un
     * salto de línea alcanzaría para falsificar una entrada de log en el formato de texto
     * de desarrollo, y una cadena enorme se repetiría en cada línea de la request.
     */
    @Test
    @DisplayName("headerEntranteConCaracteresPeligrosos_seDescartaYSeGeneraUnoLimpio")
    void headerEntranteConCaracteresPeligrosos_seDescartaYSeGeneraUnoLimpio() {
        String malicioso = "abc def ERROR entrada-falsificada";

        String devuelto = traceIdDe(getConHeader("/actuator/health", malicioso));

        assertNotNull(devuelto);
        assertNotEquals(malicioso, devuelto);
        assertTrue(devuelto.matches("[A-Za-z0-9._-]+"), "El traceId generado deberia ser seguro: " + devuelto);
    }

    @Test
    @DisplayName("headerEntranteDemasiadoLargo_seDescarta")
    void headerEntranteDemasiadoLargo_seDescarta() {
        String enorme = "a".repeat(500);

        String devuelto = traceIdDe(getConHeader("/actuator/health", enorme));

        assertNotNull(devuelto);
        assertNotEquals(enorme, devuelto);
        assertTrue(devuelto.length() <= 64);
    }
}
