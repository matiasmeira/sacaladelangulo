package com.matiasmeira.sacaladelangulo.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base para los tests adversariales que necesitan una base Postgres real: constraints
 * (índices únicos parciales como uk_turno_caja_abierto_por_establecimiento), locking
 * pesimista bajo concurrencia real con hilos de verdad, y semántica de transacción/rollback
 * que un repositorio mockeado no puede ejercitar. A diferencia de los tests con @DataJpaTest
 * + H2 que ya existen en el repo (create-drop, Flyway apagado), acá se corren las
 * migraciones de Flyway reales contra el contenedor, así que el esquema es el mismo que en
 * producción (incluye el índice único parcial de V1 y los ajustes de V2-V8).
 *
 * <p>Requiere Docker disponible en la máquina donde corre el build (Testcontainers arranca
 * el contenedor de Postgres). Si Docker no está disponible, estos tests fallan al arrancar
 * el contexto de Spring (no hay forma de saltarlos automáticamente sin ocultar el problema:
 * ver REVISION_FUNCIONAL.md, sección de cobertura de tests, para el estado real de ejecución
 * en el entorno en que se generó este reporte).
 */
@Tag("testcontainers")
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sacaladelangulo_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        // ddl-auto=validate (default de application.properties) + Flyway real: el esquema
        // creado por las migraciones tiene que coincidir exactamente con las entidades.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("jwt.secret", () -> "test-secret-de-al-menos-32-bytes-1234567890");
    }
}
