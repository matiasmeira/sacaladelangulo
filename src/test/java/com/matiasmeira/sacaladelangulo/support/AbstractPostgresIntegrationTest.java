package com.matiasmeira.sacaladelangulo.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

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
 * el contexto de Spring.
 *
 * <p><b>Contenedor singleton:</b> el Postgres se arranca UNA sola vez para toda la JVM (bloque
 * static) y NO se detiene entre clases de test. Antes se usaba @Container + @Testcontainers,
 * que arranca y para un contenedor por clase; correr dos clases de integración en la misma
 * pasada de Maven provocaba un "Connection refused" (la segunda clase quedaba apuntando a un
 * contenedor que el ciclo de vida por-clase ya había detenido). Con el patrón singleton el
 * contenedor vive toda la corrida y todas las clases lo comparten; Ryuk (el reaper de
 * Testcontainers) lo elimina al terminar la JVM.
 */
@Tag("testcontainers")
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("sacaladelangulo_test")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
    }

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Aislamiento entre métodos. Esta jerarquía NO es @Transactional a propósito: los tests
     * de concurrencia hacen commits reales desde varios hilos, y un rollback de test no
     * cubriría esos hilos ni ejercitaría el commit real que es justamente lo que se prueba.
     * Como el contenedor es singleton y compartido, sin limpieza los datos de un método se
     * arrastran al siguiente y rompen asserts globales (ej. ventaRepository.count() == 0).
     * Se truncan todas las tablas de la app (menos flyway_schema_history) antes de cada
     * método, con CASCADE (ignora el orden de las FKs) y RESTART IDENTITY.
     */
    @BeforeEach
    void limpiarBaseDeDatos() {
        List<String> tablas = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                        "AND tablename <> 'flyway_schema_history'", String.class);
        if (!tablas.isEmpty()) {
            jdbcTemplate.execute("TRUNCATE TABLE " + String.join(", ", tablas)
                    + " RESTART IDENTITY CASCADE");
        }
    }
}
