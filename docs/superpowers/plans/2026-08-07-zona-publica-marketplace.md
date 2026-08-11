# Zona Pública del Marketplace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the anonymous/public zone of the marketplace (visitor discovering and comparing complejos) its own read-only contract — `slug`-based URLs, public-safe DTOs, and three `permitAll` endpoints — without leaking `duenoId`/player data and without breaking the authenticated app.

**Architecture:** New `publico` feature package (`controller`/`service`/`dto`) that sits on top of the existing `establecimiento`/`disponibilidad`/`feedback` layers. `Establecimiento` gains `slug` (unique, backfilled), `servicios` and `fotos` (two new `@ElementCollection`s, same pattern as `Cancha.deportes`). The old public `/buscar` endpoint (which returns the internal `EstablecimientoResponse`, leaking `duenoId`) is deleted and replaced by `/api/v1/publico/complejos**`. The public disponibilidad endpoint resolves `slug → id` and delegates straight to the existing `DisponibilidadService` — its response tree already has no player data, so no new projection is needed there.

**Tech Stack:** Spring Boot 3.5.14, Java 21, Spring Data JPA (Hibernate), PostgreSQL + Flyway (prod), H2 (tests), JUnit 5 + Mockito, MockMvc.

## Global Constraints

- Java 21 / Spring Boot 3.5.14 — match existing code style exactly (Lombok `@Builder`/`@Getter`/`@Setter`, records for DTOs, `@RequiredArgsConstructor` services).
- Flyway migrations are sequential; the highest existing one is `V12__checks_montos.sql`, so the new one MUST be `V13__...sql`. Never reuse or renumber an existing version.
- `./mvnw test` (the default, no-Docker suite) must stay green. Tests tagged `testcontainers` are excluded from that command by default — don't rely on them to catch anything the default suite needs to catch.
- No new external dependencies (no slugify library, no ImageKit): slug generation and geo distance are hand-rolled; `fotos`/`servicios` are plain `String`/enum collections populated by hand for now.
- Public DTOs (`ComplejoCardResponse`, `ComplejoDetalleResponse`, `CanchaPublicaDto`) must never contain `duenoId` or any other dueño-internal field. The public disponibilidad response must never contain player/jugador data.
- Don't touch the authenticated app's existing contracts (`EstablecimientoResponse`, `/api/v1/establecimientos/**` other than removing `/buscar`) beyond what's explicitly listed below.

---

## Task 1: Schema & model — `slug`, `servicios`, `fotos`

**Files:**
- Create: `src/main/resources/db/migration/V13__zona_publica_marketplace.sql`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Servicio.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Establecimiento.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaExclusionConstraintIntegrationTest.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/cierrecaja/service/TurnoCajaConcurrenciaIntegrationTest.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaConcurrenciaIntegrationTest.java`

**Interfaces:**
- Produces: `Establecimiento.getSlug(): String`, `Establecimiento.getServicios(): Set<Servicio>`, `Establecimiento.getFotos(): List<String>` (all consumed by later tasks). `Establecimiento.builder().slug(String)` (Lombok-generated).

**Why these test files change:** `slug` becomes `nullable=false` on the entity. `EstablecimientoRepositoryTest` uses `@DataJpaTest` with `ddl-auto=create-drop`, which generates the schema straight from the entity — so any test that persists an `Establecimiento` without a `slug` will now fail a NOT NULL check. The three `testcontainers`-tagged files persist `Establecimiento` against a real Postgres (via Flyway) for the same reason. These are mechanical one-line additions, not behavior changes.

- [ ] **Step 1: Write the migration**

```sql
-- =============================================================================
-- V13 — Zona pública del marketplace: slug, servicios y fotos de establecimiento
--
-- Prepara el modelo para el namespace público /api/v1/publico/**: cada complejo
-- necesita una URL estable (slug) y algo de contenido para mostrar sin depender
-- de campos internos (dueño, etc.). fotos/servicios se cargan a mano por ahora
-- (sin integración con ImageKit todavía).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- SLUG
-- Se agrega nullable primero para poder backfillear los complejos ya existentes
-- antes de exigir NOT NULL + UNIQUE: si se agregaran ambas restricciones de
-- entrada, el ALTER TABLE fallaría contra cualquier base con datos (todas las
-- filas existentes tendrían slug NULL).
-- -----------------------------------------------------------------------------
ALTER TABLE establecimientos ADD COLUMN slug VARCHAR(255);

-- Backfill: un slug base por nombre (minúsculas, sin acentos, separado por
-- guiones) y, si colisiona con uno ya asignado en esta misma corrida, un
-- sufijo numérico incremental (-2, -3, ...) hasta encontrar uno libre. Recorre
-- los establecimientos en orden de id para que el resultado sea determinístico.
DO $$
DECLARE
    fila RECORD;
    base VARCHAR(255);
    candidato VARCHAR(255);
    sufijo INT;
BEGIN
    FOR fila IN SELECT id, nombre FROM establecimientos ORDER BY id LOOP
        base := lower(translate(fila.nombre,
            'ÁÉÍÓÚÑÜáéíóúñü',
            'AEIOUNUaeiounu'));
        base := regexp_replace(base, '[^a-z0-9]+', '-', 'g');
        base := regexp_replace(base, '^-+|-+$', '', 'g');
        IF base IS NULL OR base = '' THEN
            base := 'complejo';
        END IF;

        candidato := base;
        sufijo := 1;
        WHILE EXISTS (SELECT 1 FROM establecimientos WHERE slug = candidato) LOOP
            sufijo := sufijo + 1;
            candidato := base || '-' || sufijo;
        END LOOP;

        UPDATE establecimientos SET slug = candidato WHERE id = fila.id;
    END LOOP;
END $$;

ALTER TABLE establecimientos ALTER COLUMN slug SET NOT NULL;
ALTER TABLE establecimientos ADD CONSTRAINT uk_establecimientos_slug UNIQUE (slug);

-- -----------------------------------------------------------------------------
-- SERVICIOS (@ElementCollection de Establecimiento.servicios)
-- Mismo patrón que cancha_deportes (V1): tabla de colección simple, sin PK
-- propia, con índice sobre la FK porque Postgres no la indexa sola (ver V9/V11).
-- -----------------------------------------------------------------------------
CREATE TABLE establecimiento_servicios (
    establecimiento_id  BIGINT NOT NULL,
    servicio             VARCHAR(255) NOT NULL,
    CONSTRAINT fk_establecimiento_servicios_establecimiento
        FOREIGN KEY (establecimiento_id) REFERENCES establecimientos (id)
);

CREATE INDEX idx_establecimiento_servicios_establecimiento
    ON establecimiento_servicios (establecimiento_id);

-- -----------------------------------------------------------------------------
-- FOTOS (@ElementCollection ordenada de Establecimiento.fotos)
-- La columna "orden" persiste el índice de la lista (@OrderColumn): sin ella,
-- Hibernate no puede garantizar cuál es la "fotoPrincipal" (la primera) al
-- releer la colección. PK compuesta (establecimiento_id, orden): ya identifica
-- cada fila sin necesitar una columna id propia.
-- -----------------------------------------------------------------------------
CREATE TABLE establecimiento_fotos (
    establecimiento_id  BIGINT NOT NULL,
    orden                INTEGER NOT NULL,
    foto_url             VARCHAR(1000) NOT NULL,
    PRIMARY KEY (establecimiento_id, orden),
    CONSTRAINT fk_establecimiento_fotos_establecimiento
        FOREIGN KEY (establecimiento_id) REFERENCES establecimientos (id)
);
```

- [ ] **Step 2: Create the `Servicio` enum**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.model;

/**
 * Servicios/comodidades que un establecimiento puede ofrecer, mostrados en la
 * zona pública del marketplace (ver ComplejoDetalleResponse).
 */
public enum Servicio {
    PARRILLA,
    VESTUARIOS,
    ESTACIONAMIENTO,
    BUFFET,
    WIFI,
    DUCHAS,
    KIOSCO
}
```

- [ ] **Step 3: Add the three fields to `Establecimiento`**

Add after the existing `horariosAtencion` field (before the `dueno` field) in `Establecimiento.java`:

```java
    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * Servicios/comodidades del complejo (parrilla, vestuarios, etc.), mostrados en la
     * zona pública. Mismo patrón que Cancha.deportes: @ElementCollection en tabla propia.
     */
    @ElementCollection
    @CollectionTable(name = "establecimiento_servicios", joinColumns = @JoinColumn(name = "establecimiento_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "servicio", nullable = false)
    @lombok.Builder.Default
    private java.util.Set<Servicio> servicios = new java.util.HashSet<>();

    /**
     * URLs de fotos del complejo, en el orden en que se muestran (la primera es la
     * "fotoPrincipal" de la card pública). @OrderColumn persiste ese orden explícitamente
     * (columna "orden"): sin ella Hibernate no garantiza qué foto es la primera al releer.
     * Sin integración con ImageKit todavía: se cargan a mano / seed.
     */
    @ElementCollection
    @CollectionTable(name = "establecimiento_fotos", joinColumns = @JoinColumn(name = "establecimiento_id"))
    @OrderColumn(name = "orden")
    @Column(name = "foto_url", nullable = false)
    @lombok.Builder.Default
    private java.util.List<String> fotos = new java.util.ArrayList<>();

```

(`jakarta.persistence.*` is already wildcard-imported at the top of this file, so `@ElementCollection`/`@CollectionTable`/`@OrderColumn`/`@Enumerated`/`EnumType` need no new imports. `Servicio` is in the same package, so it needs no import either.)

- [ ] **Step 4: Fix the two `Establecimiento.builder()` calls in `EstablecimientoRepositoryTest`**

Add `.slug("cercano")` to the `cercano` builder and `.slug("lejano")` to the `Lejano` builder (right after `.direccion(...)`), e.g.:

```java
        Establecimiento cercano = entityManager.persist(Establecimiento.builder()
                .nombre("Cercano")
                .direccion("Cerca")
                .slug("cercano")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());
```

and

```java
        entityManager.persist(Establecimiento.builder()
                .nombre("Lejano")
                .direccion("Lejos")
                .slug("lejano")
                .latitud(-54.8019)
                .longitud(-68.3030)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());
```

- [ ] **Step 5: Fix the `Establecimiento.builder()` calls in the three `testcontainers`-tagged tests**

Add a unique `.slug(...)` (right after `.direccion(...)`) to each:
- `ReservaExclusionConstraintIntegrationTest.java` line ~79 (`"Club Exclusión"` → `.slug("club-exclusion")`).
- `TurnoCajaConcurrenciaIntegrationTest.java` line ~85 (`"Club Caja"` → `.slug("club-caja")`) and line ~174 (`"Otro club"` → `.slug("otro-club")`).
- `ReservaConcurrenciaIntegrationTest.java` line ~95 (`"Club Concurrencia"` → `.slug("club-concurrencia")`).

These aren't run by `./mvnw test` (tagged `testcontainers`), so there's no way to verify them in this task's normal test loop — just make the edit so the suite doesn't have a silent landmine for whoever next runs it with Docker.

- [ ] **Step 6: Run the default suite to confirm nothing broke**

Run: `./mvnw test`
Expected: BUILD SUCCESS (in particular `EstablecimientoRepositoryTest` and every other `@DataJpaTest`/Mockito test involving `Establecimiento` still pass).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V13__zona_publica_marketplace.sql src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Servicio.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/model/Establecimiento.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaExclusionConstraintIntegrationTest.java src/test/java/com/matiasmeira/sacaladelangulo/cierrecaja/service/TurnoCajaConcurrenciaIntegrationTest.java src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaConcurrenciaIntegrationTest.java
git commit -m "feat: add slug, servicios y fotos a Establecimiento (V13)"
```

---

## Task 2: Repository layer — slug lookups, deporte-only listing, batched cancha/foto fetches

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepositoryTest.java` (new)

**Interfaces:**
- Consumes: `Establecimiento`, `Cancha`, `Deporte` (existing models).
- Produces: `EstablecimientoRepository.existsBySlug(String): boolean`, `findBySlugAndIsActiveTrue(String): Optional<Establecimiento>`, `findActivosPorDeporte(Deporte): List<Establecimiento>`, `precargarFotos(List<Long>): List<Establecimiento>`; `CanchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List<Long>): List<Cancha>` — all consumed by Task 3 (SlugGenerator) and Tasks 4-5 (ComplejoPublicoService).

- [ ] **Step 1: Write the failing repository test for `EstablecimientoRepository`**

Add to `EstablecimientoRepositoryTest.java` (same class, same `@DataJpaTest` setup already in the file):

```java
    @Test
    @DisplayName("existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado")
    void existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno2@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Uno")
                .direccion("Calle Uno")
                .slug("complejo-uno")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.flush();

        assertTrue(establecimientoRepository.existsBySlug("complejo-uno"));
        assertFalse(establecimientoRepository.existsBySlug("complejo-dos"));
    }

    @Test
    @DisplayName("findBySlugAndIsActiveTrue_NoDevuelveComplejosInactivos")
    void findBySlugAndIsActiveTrue_NoDevuelveComplejosInactivos() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno3@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Inactivo")
                .direccion("Calle Dos")
                .slug("complejo-inactivo")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(false)
                .dueno(dueno)
                .build());
        entityManager.flush();

        assertTrue(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-inactivo").isEmpty());
        assertTrue(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe").isEmpty());
    }

    @Test
    @DisplayName("findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas")
    void findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno4@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento conPadel = entityManager.persist(Establecimiento.builder()
                .nombre("Con Padel")
                .direccion("Calle Tres")
                .slug("con-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(java.util.Set.of(Deporte.PADEL))
                .capacidad(4)
                .isActive(true)
                .precioBase(java.math.BigDecimal.valueOf(1000))
                .montoSena(java.math.BigDecimal.valueOf(200))
                .establecimiento(conPadel)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Sin Padel")
                .direccion("Calle Cuatro")
                .slug("sin-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository.findActivosPorDeporte(Deporte.PADEL);

        assertEquals(1, resultado.size());
        assertEquals(conPadel.getId(), resultado.get(0).getId());
        assertEquals(2, establecimientoRepository.findActivosPorDeporte(null).size());
    }
```

Add the missing imports at the top of the test file: `com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha`, `com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte`, and static imports `assertFalse`, `assertTrue` (alongside the existing `assertEquals`).

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=EstablecimientoRepositoryTest`
Expected: compile error / test failure — `existsBySlug`, `findBySlugAndIsActiveTrue`, `findActivosPorDeporte` don't exist yet on `EstablecimientoRepository`.

- [ ] **Step 3: Implement the new `EstablecimientoRepository` methods**

Add to `EstablecimientoRepository.java` (add `Optional` and `EntityGraph` imports):

```java
import org.springframework.data.jpa.repository.EntityGraph;
...
import java.util.Optional;
```

```java
    boolean existsBySlug(String slug);

    Optional<Establecimiento> findBySlugAndIsActiveTrue(String slug);

    /**
     * Variante de findCercanosYPorDeporte sin filtro geográfico: alimenta el listado
     * público cuando el visitante no compartió su ubicación (home sin filtros), donde
     * el orden relevante es el rating y no la distancia.
     */
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes)")
    List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte);

    /**
     * Trae, para el lote de ids indicado, las fotos (@ElementCollection ordenada) ya
     * inicializadas en la misma consulta: evita un SELECT de fotos por establecimiento al
     * armar la card pública (fotoPrincipal = primera foto). Las entidades que devuelve son,
     * dentro de la misma transacción, las mismas instancias gestionadas por la sesión que
     * ya trajo el listado principal — alcanza con llamar a este método por su efecto de
     * precarga; el caller sigue usando las entidades originales.
     */
    @EntityGraph(attributePaths = {"fotos"})
    @Query("SELECT e FROM Establecimiento e WHERE e.id IN :ids")
    List<Establecimiento> precargarFotos(@Param("ids") List<Long> ids);
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=EstablecimientoRepositoryTest`
Expected: PASS (all tests in the class, old and new).

- [ ] **Step 5: Write the failing test for `CanchaRepository`'s new batch fetch**

Create `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepositoryTest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("CanchaRepository - Fetch en lote con deportes y tarifas")
class CanchaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CanchaRepository canchaRepository;

    @Test
    @DisplayName("findActivasConDeportesYTarifasByEstablecimientoIdIn_TraeDeportesYTarifasSinLazyException")
    void findActivasConDeportesYTarifasByEstablecimientoIdIn_TraeDeportesYTarifasSinLazyException() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle Test")
                .slug("complejo-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .establecimiento(establecimiento)
                .build());
        entityManager.persist(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(6000))
                .build());
        entityManager.flush();
        entityManager.clear();

        List<Cancha> resultado = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(establecimiento.getId()));

        assertEquals(1, resultado.size());
        assertTrue(resultado.get(0).getDeportes().contains(Deporte.FUTBOL));
        assertEquals(1, resultado.get(0).getTarifas().size());
        assertEquals(BigDecimal.valueOf(6000), resultado.get(0).getTarifas().get(0).getPrecio());
    }
}
```

- [ ] **Step 6: Run to see it fail**

Run: `./mvnw test -Dtest=CanchaRepositoryTest`
Expected: compile error — `findActivasConDeportesYTarifasByEstablecimientoIdIn` doesn't exist yet.

- [ ] **Step 7: Implement the `CanchaRepository` changes**

Add to `CanchaRepository.java`:

```java
    /**
     * Trae, para el lote de establecimientos indicado, sus canchas activas con deportes y
     * tarifas ya inicializados en la misma consulta (@EntityGraph): alimenta las
     * derivaciones públicas (deportes/precioDesde/senaDesde por complejo, ver
     * ComplejoPublicoService) sin ejecutar una consulta de tarifas por cancha (N+1).
     * "tarifas" es la única colección tipo lista (bag) del grafo — "deportes" es un Set —
     * así que no cae en MultipleBagFetchException.
     */
    @EntityGraph(attributePaths = {"deportes", "tarifas"})
    @Query("SELECT c FROM Cancha c WHERE c.establecimiento.id IN :establecimientoIds AND c.isActive = true")
    List<Cancha> findActivasConDeportesYTarifasByEstablecimientoIdIn(@Param("establecimientoIds") List<Long> establecimientoIds);
```

Also change the existing `findByEstablecimientoIdInAndIsActiveTrue` to eagerly fetch `deportes` (its only caller after Task 7 is the availability filter in `ComplejoPublicoService`, which reads `c.getDeportes()` in a loop — without this it's a lazy N+1; `deportes` is a `Set`, so adding it alongside no other bag-fetch on this method is safe):

```java
    /**
     * Variante en lote para búsquedas que abarcan varios establecimientos a la vez (evita
     * hacer una consulta por establecimiento). @EntityGraph sobre "deportes": los callers
     * de este método filtran por c.getDeportes().contains(...) en memoria sobre el
     * resultado completo (hoy EstablecimientoService.buscarEstablecimientos; a partir de la
     * Tarea 7 de este plan, el filtro de disponibilidad por fecha/hora de
     * ComplejoPublicoService) — sin este fetch, ese filtro dispara un SELECT de deportes
     * por cada cancha (N+1).
     */
    @EntityGraph(attributePaths = {"deportes"})
    List<Cancha> findByEstablecimientoIdInAndIsActiveTrue(List<Long> establecimientoIds);
```

- [ ] **Step 8: Run to see it pass**

Run: `./mvnw test -Dtest=CanchaRepositoryTest`
Expected: PASS.

- [ ] **Step 9: Run the full default suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepository.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepositoryTest.java
git commit -m "feat: repositorios para slug, listado sin ubicación y fetch en lote de deportes/tarifas"
```

---

## Task 3: `GeoUtils` + `SlugGenerator`, wired into `crearEstablecimiento`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/GeoUtils.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/SlugGenerator.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/GeoUtilsTest.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/SlugGeneratorTest.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoServiceTest.java`

**Interfaces:**
- Consumes: `EstablecimientoRepository.existsBySlug` (Task 2).
- Produces: `GeoUtils.distanciaKm(double,double,double,double): double` and `SlugGenerator.generarSlugUnico(String): String` — both consumed by Task 4/5 (`ComplejoPublicoService`) and `EstablecimientoService.crearEstablecimiento`.

- [ ] **Step 1: Write the failing `SlugGeneratorTest`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlugGenerator - Generación de slug único")
class SlugGeneratorTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @InjectMocks
    private SlugGenerator slugGenerator;

    @Test
    @DisplayName("generarSlugUnico_NombreSimple_DevuelveSlugMinusculaConGuiones")
    void generarSlugUnico_NombreSimple_DevuelveSlugMinusculaConGuiones() {
        when(establecimientoRepository.existsBySlug("cancha-norte")).thenReturn(false);

        assertEquals("cancha-norte", slugGenerator.generarSlugUnico("Cancha Norte"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreConAcentosYEnie_NormalizaCaracteres")
    void generarSlugUnico_NombreConAcentosYEnie_NormalizaCaracteres() {
        when(establecimientoRepository.existsBySlug("futbol-5-nunoa")).thenReturn(false);

        assertEquals("futbol-5-nunoa", slugGenerator.generarSlugUnico("Fútbol 5 Ñuñoa"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreDuplicado_AgregaSufijoNumericoHastaEncontrarUnoLibre")
    void generarSlugUnico_NombreDuplicado_AgregaSufijoNumericoHastaEncontrarUnoLibre() {
        when(establecimientoRepository.existsBySlug("cancha-norte")).thenReturn(true);
        when(establecimientoRepository.existsBySlug("cancha-norte-2")).thenReturn(true);
        when(establecimientoRepository.existsBySlug("cancha-norte-3")).thenReturn(false);

        assertEquals("cancha-norte-3", slugGenerator.generarSlugUnico("Cancha Norte"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreSinCaracteresAlfanumericos_UsaFallbackComplejo")
    void generarSlugUnico_NombreSinCaracteresAlfanumericos_UsaFallbackComplejo() {
        when(establecimientoRepository.existsBySlug("complejo")).thenReturn(false);

        assertEquals("complejo", slugGenerator.generarSlugUnico("!!!"));
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=SlugGeneratorTest`
Expected: compile error — `SlugGenerator` doesn't exist yet.

- [ ] **Step 3: Implement `SlugGenerator`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Genera el slug público de un establecimiento a partir de su nombre: normaliza
 * acentos, lo pasa a minúsculas y lo separa por guiones. Si el slug base ya existe
 * (otro complejo con nombre igual o muy similar), le agrega un sufijo numérico
 * incremental hasta encontrar uno libre (ver FASE 1 del contrato de zona pública).
 */
@Component
@RequiredArgsConstructor
public class SlugGenerator {

    private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern GUIONES_BORDE = Pattern.compile("^-+|-+$");

    private final EstablecimientoRepository establecimientoRepository;

    public String generarSlugUnico(String nombre) {
        String base = normalizar(nombre);
        String candidato = base;
        int sufijo = 1;
        while (establecimientoRepository.existsBySlug(candidato)) {
            sufijo++;
            candidato = base + "-" + sufijo;
        }
        return candidato;
    }

    private String normalizar(String nombre) {
        String descompuesto = Normalizer.normalize(nombre.toLowerCase(), Normalizer.Form.NFD);
        String sinAcentos = DIACRITICOS.matcher(descompuesto).replaceAll("");
        String slug = NO_ALFANUMERICO.matcher(sinAcentos).replaceAll("-");
        slug = GUIONES_BORDE.matcher(slug).replaceAll("");
        return slug.isEmpty() ? "complejo" : slug;
    }
}
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=SlugGeneratorTest`
Expected: PASS.

- [ ] **Step 5: Write the failing `GeoUtilsTest`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GeoUtils - Distancia Haversine")
class GeoUtilsTest {

    @Test
    @DisplayName("distanciaKm_MismoPunto_DevuelveCero")
    void distanciaKm_MismoPunto_DevuelveCero() {
        assertEquals(0.0, GeoUtils.distanciaKm(-34.6037, -58.3816, -34.6037, -58.3816), 0.0001);
    }

    @Test
    @DisplayName("distanciaKm_ObeliscoAUshuaia_DevuelveAproximadamente2500Km")
    void distanciaKm_ObeliscoAUshuaia_DevuelveAproximadamente2500Km() {
        double distancia = GeoUtils.distanciaKm(-34.6037, -58.3816, -54.8019, -68.3030);
        assertTrue(distancia > 2400 && distancia < 2600, "Esperaba ~2500km, fue " + distancia);
    }
}
```

- [ ] **Step 6: Run to see it fail**

Run: `./mvnw test -Dtest=GeoUtilsTest`
Expected: compile error — `GeoUtils` doesn't exist yet.

- [ ] **Step 7: Implement `GeoUtils`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

/**
 * Cálculo puro de distancia entre dos coordenadas (fórmula de Haversine), usado para
 * mostrarle al visitante la distancia a un complejo en los listados públicos. Mismo radio
 * terrestre (6371 km) que usa el filtro geográfico de
 * EstablecimientoRepository.findCercanosYPorDeporte, para que el número que se muestra sea
 * consistente con el que se usó para filtrar.
 */
public final class GeoUtils {

    private static final double RADIO_TIERRA_KM = 6371.0;

    private GeoUtils() {
    }

    public static double distanciaKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return RADIO_TIERRA_KM * c;
    }
}
```

- [ ] **Step 8: Run to see it pass**

Run: `./mvnw test -Dtest=GeoUtilsTest`
Expected: PASS.

- [ ] **Step 9: Wire `SlugGenerator` into `EstablecimientoService.crearEstablecimiento`**

In `EstablecimientoService.java`, add the field (Lombok `@RequiredArgsConstructor` picks it up automatically):

```java
    private final SlugGenerator slugGenerator;
```

placed alongside the other repository/service fields (e.g. right after `private final AutorizacionEmpleadoService autorizacionEmpleadoService;`).

Change the `crearEstablecimiento` builder call from:

```java
        Establecimiento establecimiento = Establecimiento.builder()
                .nombre(request.nombre())
                .direccion(request.direccion())
                .latitud(request.latitud())
                .longitud(request.longitud())
                .requiereSena(requiereSenaForzada || request.requiereSena())
                .isActive(true)
                .dueno(dueno)
                .build();
```

to:

```java
        Establecimiento establecimiento = Establecimiento.builder()
                .nombre(request.nombre())
                .direccion(request.direccion())
                .latitud(request.latitud())
                .longitud(request.longitud())
                .requiereSena(requiereSenaForzada || request.requiereSena())
                .isActive(true)
                .slug(slugGenerator.generarSlugUnico(request.nombre()))
                .dueno(dueno)
                .build();
```

- [ ] **Step 10: Update `EstablecimientoServiceTest` for the new constructor dependency**

Add to the test class (alongside the other `@Mock` fields):

```java
    @Mock
    private SlugGenerator slugGenerator;
```

(Without this, Mockito's constructor injection would pass a plain `null` for the new `SlugGenerator` parameter — since there's no `@Mock` of that type to inject — and `crearEstablecimiento` would NPE calling `slugGenerator.generarSlugUnico(...)`. With a real mock present, unstubbed calls just return `null`, which is harmless here since none of the existing assertions inspect the slug.)

- [ ] **Step 11: Run the affected tests, then the full suite**

Run: `./mvnw test -Dtest=EstablecimientoServiceTest`
Expected: PASS.

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/GeoUtils.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/SlugGenerator.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/GeoUtilsTest.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/SlugGeneratorTest.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoService.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoServiceTest.java
git commit -m "feat: generación de slug único al crear un establecimiento"
```

---

## Task 4: Public listing — `ComplejoCardResponse` + `ComplejoPublicoService.buscarComplejos`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/ComplejoCardResponse.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Consumes: `EstablecimientoRepository.findCercanosYPorDeporte/findActivosPorDeporte` (existing/Task 2), `CanchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn/findByEstablecimientoIdInAndIsActiveTrue` (Task 2), `ReservaRepository.findCanchaIdsConSolapamiento` (existing), `FeedbackRepository.calcularPromediosPorEstablecimientos/contarPorEstablecimientos` (existing), `GeoUtils.distanciaKm` (Task 3).
- Produces: `ComplejoPublicoService.buscarComplejos(Double,Double,Double,Deporte,LocalDate,LocalTime,Pageable): Page<ComplejoCardResponse>` — consumed by Task 7 (controller). `ComplejoCardResponse` record — consumed by Task 7/8.

- [ ] **Step 1: Create the `ComplejoCardResponse` DTO**

```java
package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Card de un complejo en el listado público (home o búsqueda). No incluye duenoId ni
 * ningún otro dato interno del dueño (ver contrato de zona pública).
 */
public record ComplejoCardResponse(
        String slug,
        String nombre,
        String direccion,
        String fotoPrincipal,
        Set<Deporte> deportes,
        BigDecimal precioDesde,
        Boolean requiereSena,
        BigDecimal senaDesde,
        Double distanciaKm,
        Double promedioCalificacion,
        Long cantidadCalificaciones
) {
}
```

- [ ] **Step 2: Write the failing `ComplejoPublicoServiceTest` (listing cases)**

```java
package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComplejoPublicoService - Listado público de complejos")
class ComplejoPublicoServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private DisponibilidadService disponibilidadService;

    @InjectMocks
    private ComplejoPublicoService complejoPublicoService;

    private Establecimiento establecimiento(Long id, String slug, String nombre, boolean requiereSena) {
        return Establecimiento.builder()
                .id(id)
                .nombre(nombre)
                .direccion("Direccion " + id)
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(requiereSena)
                .isActive(true)
                .build();
    }

    private Cancha canchaConTarifa(Long id, Establecimiento est, Set<Deporte> deportes, BigDecimal montoSena, BigDecimal precioTarifa) {
        Cancha cancha = Cancha.builder()
                .id(id)
                .nombre("Cancha " + id)
                .deportes(deportes)
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(montoSena)
                .establecimiento(est)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(precioTarifa)
                .build()));
        return cancha;
    }

    @Test
    @DisplayName("buscarComplejos_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeSinFiltroDeDeporte")
    void buscarComplejos_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeSinFiltroDeDeporte() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), card.deportes());
        assertEquals(BigDecimal.valueOf(3000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(800), card.senaDesde());
        assertNull(card.distanciaKm());
    }

    @Test
    @DisplayName("buscarComplejos_ConFiltroDeDeporte_AcotaPrecioDesdeYSenaDesdeALasCanchasDeEseDeporte")
    void buscarComplejos_ConFiltroDeDeporte_AcotaPrecioDesdeYSenaDesdeALasCanchasDeEseDeporte() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findActivosPorDeporte(Deporte.PADEL)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, Deporte.PADEL, null, null, PageRequest.of(0, 20));

        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), card.deportes());
        assertEquals(BigDecimal.valueOf(3000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(800), card.senaDesde());
    }

    @Test
    @DisplayName("buscarComplejos_ConUbicacion_OrdenaPorDistanciaAscendente")
    void buscarComplejos_ConUbicacion_OrdenaPorDistanciaAscendente() {
        Establecimiento lejano = Establecimiento.builder()
                .id(1L).nombre("Lejano").direccion("D1").slug("lejano")
                .latitud(-54.8019).longitud(-68.3030).requiereSena(false).isActive(true).build();
        Establecimiento cercano = Establecimiento.builder()
                .id(2L).nombre("Cercano").direccion("D2").slug("cercano")
                .latitud(-34.6037).longitud(-58.3816).requiereSena(false).isActive(true).build();

        when(establecimientoRepository.findCercanosYPorDeporte(-34.6037, -58.3816, 10.0, null))
                .thenReturn(List.of(lejano, cercano));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L))).thenReturn(List.of(lejano, cercano));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                -34.6037, -58.3816, null, null, null, null, PageRequest.of(0, 20));

        assertEquals("cercano", resultado.getContent().get(0).slug());
        assertEquals("lejano", resultado.getContent().get(1).slug());
    }

    @Test
    @DisplayName("buscarComplejos_Paginacion_RespetaPageYSize")
    void buscarComplejos_Paginacion_RespetaPageYSize() {
        Establecimiento e1 = establecimiento(1L, "uno", "Uno", false);
        Establecimiento e2 = establecimiento(2L, "dos", "Dos", false);
        Establecimiento e3 = establecimiento(3L, "tres", "Tres", false);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(e1, e2, e3));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of());
        when(establecimientoRepository.precargarFotos(List.of(1L, 2L, 3L))).thenReturn(List.of(e1, e2, e3));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L, 2L, 3L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L, 2L, 3L))).thenReturn(List.of());

        Page<ComplejoCardResponse> primeraPagina = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 2));

        assertEquals(2, primeraPagina.getContent().size());
        assertEquals(3, primeraPagina.getTotalElements());
        assertEquals(2, primeraPagina.getTotalPages());
    }
}
```

- [ ] **Step 3: Run to see it fail**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: compile error — `ComplejoPublicoService` doesn't exist yet.

- [ ] **Step 4: Implement `ComplejoPublicoService` (listing half)**

```java
package com.matiasmeira.sacaladelangulo.publico.service;

import com.matiasmeira.sacaladelangulo.disponibilidad.service.DisponibilidadService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.GeoUtils;
import com.matiasmeira.sacaladelangulo.feedback.repository.FeedbackRepository;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Zona pública del marketplace: listado, detalle y disponibilidad de complejos para un
 * visitante anónimo. Ninguno de los DTOs que devuelve incluye duenoId ni otro dato interno
 * del dueño (ver contrato de zona pública).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComplejoPublicoService {

    private static final double RADIO_BUSQUEDA_DEFAULT_KM = 10.0;
    private static final int VENTANA_DISPONIBILIDAD_MINUTOS = 60;

    private final EstablecimientoRepository establecimientoRepository;
    private final CanchaRepository canchaRepository;
    private final ReservaRepository reservaRepository;
    private final FeedbackRepository feedbackRepository;
    private final DisponibilidadService disponibilidadService;

    /**
     * Listado público de complejos: sirve tanto al home (sin lat/lng, ordenado por rating)
     * como a la búsqueda con ubicación (ordenado por distancia). Cuando se pide fecha/hora,
     * filtra a los complejos con al menos una cancha libre en esa ventana -- ese filtro no
     * es una columna de base, así que en ese caso se pagina en memoria sobre el conjunto ya
     * acotado por geo/deporte a nivel de base (mismo trade-off que ya tenía el viejo
     * /buscar, que tampoco paginaba).
     */
    public Page<ComplejoCardResponse> buscarComplejos(Double lat, Double lng, Double distanciaKm, Deporte deporte,
            LocalDate fecha, LocalTime hora, Pageable pageable) {
        validarUbicacion(lat, lng);
        boolean conUbicacion = lat != null && lng != null;
        Double radio = (distanciaKm != null && distanciaKm > 0) ? distanciaKm : RADIO_BUSQUEDA_DEFAULT_KM;

        List<Establecimiento> candidatos = conUbicacion
                ? establecimientoRepository.findCercanosYPorDeporte(lat, lng, radio, deporte)
                : establecimientoRepository.findActivosPorDeporte(deporte);

        if (fecha != null && hora != null) {
            candidatos = filtrarPorDisponibilidad(candidatos, deporte, fecha, hora);
        }

        List<ComplejoCardResponse> cards = mapearACards(candidatos, conUbicacion ? lat : null, conUbicacion ? lng : null, deporte);
        List<ComplejoCardResponse> ordenados = conUbicacion
                ? cards.stream()
                        .sorted(Comparator.comparing(ComplejoCardResponse::distanciaKm, Comparator.nullsLast(Comparator.naturalOrder())))
                        .toList()
                : cards.stream()
                        .sorted(Comparator.comparing(ComplejoCardResponse::promedioCalificacion, Comparator.nullsLast(Comparator.reverseOrder())))
                        .toList();

        return paginarEnMemoria(ordenados, pageable);
    }

    private void validarUbicacion(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new IllegalArgumentException("lat y lng deben proveerse juntos");
        }
    }

    private List<Establecimiento> filtrarPorDisponibilidad(List<Establecimiento> candidatos, Deporte deporte, LocalDate fecha, LocalTime hora) {
        List<Long> establecimientoIds = candidatos.stream().map(Establecimiento::getId).toList();
        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(establecimientoIds);
        if (deporte != null) {
            canchas = canchas.stream().filter(c -> c.getDeportes().contains(deporte)).toList();
        }

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchas.stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        LocalDateTime inicioReserva = LocalDateTime.of(fecha, hora);
        LocalDateTime finReserva = inicioReserva.plusMinutes(VENTANA_DISPONIBILIDAD_MINUTOS);

        List<Long> canchaIds = canchas.stream().map(Cancha::getId).toList();
        Set<Long> canchasOcupadas = canchaIds.isEmpty()
                ? Set.of()
                : new HashSet<>(reservaRepository.findCanchaIdsConSolapamiento(canchaIds, inicioReserva, finReserva));

        return candidatos.stream()
                .filter(est -> canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()).stream()
                        .anyMatch(c -> !canchasOcupadas.contains(c.getId())))
                .toList();
    }

    private List<ComplejoCardResponse> mapearACards(List<Establecimiento> establecimientos, Double lat, Double lng, Deporte deporte) {
        if (establecimientos.isEmpty()) {
            return List.of();
        }

        List<Long> ids = establecimientos.stream().map(Establecimiento::getId).toList();
        establecimientoRepository.precargarFotos(ids);

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(ids).stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        Map<Long, Double> promedios = new HashMap<>();
        for (Object[] fila : feedbackRepository.calcularPromediosPorEstablecimientos(ids)) {
            promedios.put((Long) fila[0], (Double) fila[1]);
        }
        Map<Long, Long> cantidades = new HashMap<>();
        for (Object[] fila : feedbackRepository.contarPorEstablecimientos(ids)) {
            cantidades.put((Long) fila[0], (Long) fila[1]);
        }

        return establecimientos.stream()
                .map(est -> construirCard(est, canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()), deporte,
                        lat, lng, promedios.get(est.getId()), cantidades.getOrDefault(est.getId(), 0L)))
                .toList();
    }

    private ComplejoCardResponse construirCard(Establecimiento establecimiento, List<Cancha> canchas, Deporte deporte,
            Double lat, Double lng, Double promedioCalificacion, Long cantidadCalificaciones) {

        Set<Deporte> deportes = canchas.stream().flatMap(c -> c.getDeportes().stream()).collect(Collectors.toSet());
        List<Cancha> relevantes = deporte == null
                ? canchas
                : canchas.stream().filter(c -> c.getDeportes().contains(deporte)).toList();

        BigDecimal precioDesde = relevantes.stream()
                .flatMap(c -> c.getTarifas().stream())
                .map(Tarifa::getPrecio)
                .min(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal senaDesde = relevantes.stream()
                .map(Cancha::getMontoSena)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        String fotoPrincipal = establecimiento.getFotos().isEmpty() ? null : establecimiento.getFotos().get(0);
        Double distanciaKm = (lat != null && lng != null)
                ? GeoUtils.distanciaKm(lat, lng, establecimiento.getLatitud(), establecimiento.getLongitud())
                : null;

        return new ComplejoCardResponse(
                establecimiento.getSlug(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                fotoPrincipal,
                deportes,
                precioDesde,
                establecimiento.getRequiereSena(),
                senaDesde,
                distanciaKm,
                promedioCalificacion,
                cantidadCalificaciones
        );
    }

    private Page<ComplejoCardResponse> paginarEnMemoria(List<ComplejoCardResponse> items, Pageable pageable) {
        int total = items.size();
        int desde = Math.min((int) pageable.getOffset(), total);
        int hasta = Math.min(desde + pageable.getPageSize(), total);
        return new PageImpl<>(items.subList(desde, hasta), pageable, total);
    }
}
```

- [ ] **Step 5: Run to see it pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/ComplejoCardResponse.java src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "feat: listado público de complejos con derivaciones de deportes/precio/seña"
```

---

## Task 5: Public detail — `ComplejoDetalleResponse` / `CanchaPublicaDto` + `obtenerDetalle`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/CanchaPublicaDto.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/ComplejoDetalleResponse.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Consumes: `EstablecimientoRepository.findBySlugAndIsActiveTrue` (Task 2), `CanchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn` (Task 2), `FeedbackRepository.calcularPromedioByEstablecimientoId/contarByEstablecimientoId/findDestacadoByEstablecimientoId` (existing), `HorarioAtencionDto`/`FeedbackDestacadoDto` (existing, reused as-is — already public-safe).
- Produces: `ComplejoPublicoService.obtenerDetalle(String): ComplejoDetalleResponse` (throws `EntityNotFoundException` on missing/inactive slug) — consumed by Task 7 (controller).

- [ ] **Step 1: Create `CanchaPublicaDto`**

```java
package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.math.BigDecimal;
import java.util.Set;

public record CanchaPublicaDto(
        Long id,
        String nombre,
        Set<Deporte> deportes,
        BigDecimal precioDesde
) {
}
```

- [ ] **Step 2: Create `ComplejoDetalleResponse`**

```java
package com.matiasmeira.sacaladelangulo.publico.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.FeedbackDestacadoDto;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Detalle público de un complejo. No incluye duenoId ni ningún otro dato interno del
 * dueño (ver contrato de zona pública).
 */
public record ComplejoDetalleResponse(
        String slug,
        String nombre,
        String direccion,
        Double latitud,
        Double longitud,
        Set<Deporte> deportes,
        Set<Servicio> servicios,
        List<String> fotos,
        List<HorarioAtencionDto> horariosAtencion,
        List<CanchaPublicaDto> canchas,
        BigDecimal precioDesde,
        Boolean requiereSena,
        BigDecimal senaDesde,
        Double promedioCalificacion,
        Long cantidadCalificaciones,
        FeedbackDestacadoDto comentarioDestacado
) {
}
```

- [ ] **Step 3: Write the failing tests for `obtenerDetalle`**

Add to `ComplejoPublicoServiceTest.java` (add imports `EntityNotFoundException`, `FeedbackDestacadoDto`, `Feedback`/`Reserva`/`Usuario` only if needed, `Optional`, `ComplejoDetalleResponse`, `assertThrows`, `assertTrue`):

```java
    @Test
    @DisplayName("obtenerDetalle_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeYListaCanchas")
    void obtenerDetalle_VariasCanchas_DerivaDeportesPrecioDesdeYSenaDesdeYListaCanchas() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha futbol = canchaConTarifa(10L, est, Set.of(Deporte.FUTBOL), BigDecimal.valueOf(1000), BigDecimal.valueOf(5000));
        Cancha padel = canchaConTarifa(11L, est, Set.of(Deporte.PADEL), BigDecimal.valueOf(800), BigDecimal.valueOf(3000));

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(futbol, padel));
        when(feedbackRepository.calcularPromedioByEstablecimientoId(1L)).thenReturn(4.5);
        when(feedbackRepository.contarByEstablecimientoId(1L)).thenReturn(2L);
        when(feedbackRepository.findDestacadoByEstablecimientoId(1L)).thenReturn(java.util.Optional.empty());

        com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse detalle =
                complejoPublicoService.obtenerDetalle("complejo-uno");

        assertEquals(Set.of(Deporte.FUTBOL, Deporte.PADEL), detalle.deportes());
        assertEquals(BigDecimal.valueOf(3000), detalle.precioDesde());
        assertEquals(BigDecimal.valueOf(800), detalle.senaDesde());
        assertEquals(2, detalle.canchas().size());
        assertEquals(4.5, detalle.promedioCalificacion());
    }

    @Test
    @DisplayName("obtenerDetalle_SlugInexistente_LanzaEntityNotFoundException")
    void obtenerDetalle_SlugInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDetalle("no-existe"));
    }

    @Test
    @DisplayName("obtenerDetalle_ComplejoInactivo_LanzaEntityNotFoundException")
    void obtenerDetalle_ComplejoInactivo_LanzaEntityNotFoundException() {
        // findBySlugAndIsActiveTrue ya filtra por isActive=true en el repositorio: un
        // complejo inactivo llega acá como Optional vacío, igual que un slug inexistente.
        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-inactivo")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDetalle("complejo-inactivo"));
    }
```

Add `assertThrows` to the static imports.

- [ ] **Step 4: Run to see it fail**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: compile error — `obtenerDetalle` doesn't exist yet.

- [ ] **Step 5: Implement `obtenerDetalle` in `ComplejoPublicoService`**

Add imports: `com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException`, `com.matiasmeira.sacaladelangulo.establecimiento.dto.FeedbackDestacadoDto`, `com.matiasmeira.sacaladelangulo.establecimiento.dto.HorarioAtencionDto`, `com.matiasmeira.sacaladelangulo.feedback.model.Feedback`, `com.matiasmeira.sacaladelangulo.auth.model.Usuario`, `com.matiasmeira.sacaladelangulo.publico.dto.CanchaPublicaDto`, `com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse`.

Add to `ComplejoPublicoService.java`:

```java
    /**
     * Detalle público de un complejo activo. 404 si el slug no existe o si el complejo
     * está inactivo (findBySlugAndIsActiveTrue ya filtra eso, así que ambos casos llegan
     * acá como Optional vacío).
     */
    public ComplejoDetalleResponse obtenerDetalle(String slug) {
        Establecimiento establecimiento = establecimientoRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));

        List<Cancha> canchas = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(establecimiento.getId()));

        Set<Deporte> deportes = canchas.stream().flatMap(c -> c.getDeportes().stream()).collect(Collectors.toSet());
        BigDecimal precioDesde = canchas.stream()
                .flatMap(c -> c.getTarifas().stream())
                .map(Tarifa::getPrecio)
                .min(Comparator.naturalOrder())
                .orElse(null);
        BigDecimal senaDesde = canchas.stream()
                .map(Cancha::getMontoSena)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

        List<CanchaPublicaDto> canchasPublicas = canchas.stream()
                .map(c -> new CanchaPublicaDto(
                        c.getId(),
                        c.getNombre(),
                        c.getDeportes(),
                        c.getTarifas().stream().map(Tarifa::getPrecio).min(Comparator.naturalOrder()).orElse(null)))
                .toList();

        List<HorarioAtencionDto> horarios = establecimiento.getHorariosAtencion() == null ? List.of()
                : establecimiento.getHorariosAtencion().stream()
                        .map(h -> new HorarioAtencionDto(h.getDiaSemana(), h.getHoraApertura(), h.getHoraCierre()))
                        .toList();

        Double promedio = feedbackRepository.calcularPromedioByEstablecimientoId(establecimiento.getId());
        Long cantidad = feedbackRepository.contarByEstablecimientoId(establecimiento.getId());
        FeedbackDestacadoDto destacado = feedbackRepository.findDestacadoByEstablecimientoId(establecimiento.getId())
                .map(this::mapFeedbackDestacado)
                .orElse(null);

        return new ComplejoDetalleResponse(
                establecimiento.getSlug(),
                establecimiento.getNombre(),
                establecimiento.getDireccion(),
                establecimiento.getLatitud(),
                establecimiento.getLongitud(),
                deportes,
                establecimiento.getServicios(),
                establecimiento.getFotos(),
                horarios,
                canchasPublicas,
                precioDesde,
                establecimiento.getRequiereSena(),
                senaDesde,
                promedio,
                cantidad != null ? cantidad : 0L,
                destacado
        );
    }

    private FeedbackDestacadoDto mapFeedbackDestacado(Feedback feedback) {
        Usuario jugador = feedback.getReserva().getJugador();
        return new FeedbackDestacadoDto(
                feedback.getId(),
                feedback.getPuntuacion(),
                feedback.getComentario(),
                jugador != null ? jugador.getNombre() : null,
                feedback.getFechaCreacion()
        );
    }
```

- [ ] **Step 6: Run to see it pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/CanchaPublicaDto.java src/main/java/com/matiasmeira/sacaladelangulo/publico/dto/ComplejoDetalleResponse.java src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "feat: detalle público de un complejo por slug"
```

---

## Task 6: Public disponibilidad passthrough

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Consumes: `DisponibilidadService.obtenerDisponibilidad(Long,LocalDate,LocalDate): DisponibilidadEstablecimientoResponse` (existing, unchanged — its response tree has no player data already).
- Produces: `ComplejoPublicoService.obtenerDisponibilidad(String,LocalDate,LocalDate): DisponibilidadEstablecimientoResponse` — consumed by Task 7 (controller).

- [ ] **Step 1: Write the failing tests**

Add to `ComplejoPublicoServiceTest.java` (add import `com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse`, `java.time.LocalDate`, `eq`):

```java
    @Test
    @DisplayName("obtenerDisponibilidad_ResuelveSlugYDelegaEnDisponibilidadService")
    void obtenerDisponibilidad_ResuelveSlugYDelegaEnDisponibilidadService() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        LocalDate fecha = LocalDate.of(2026, 8, 10);
        com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse respuestaEsperada =
                new com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse(1L, fecha, fecha, List.of());

        when(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-uno")).thenReturn(java.util.Optional.of(est));
        when(disponibilidadService.obtenerDisponibilidad(1L, fecha, fecha)).thenReturn(respuestaEsperada);

        var resultado = complejoPublicoService.obtenerDisponibilidad("complejo-uno", fecha, fecha);

        assertEquals(respuestaEsperada, resultado);
    }

    @Test
    @DisplayName("obtenerDisponibilidad_SlugInexistente_LanzaEntityNotFoundException")
    void obtenerDisponibilidad_SlugInexistente_LanzaEntityNotFoundException() {
        LocalDate fecha = LocalDate.of(2026, 8, 10);
        when(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe")).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> complejoPublicoService.obtenerDisponibilidad("no-existe", fecha, fecha));
    }
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: compile error — `obtenerDisponibilidad` doesn't exist yet.

- [ ] **Step 3: Implement `obtenerDisponibilidad`**

Add import `com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse` to `ComplejoPublicoService.java`, and add:

```java
    /**
     * Disponibilidad pública de un complejo activo: resuelve slug -> id y reusa
     * DisponibilidadService tal cual, sin proyección propia. Su árbol de respuesta
     * (DisponibilidadEstablecimientoResponse -> DisponibilidadDiaResponse ->
     * DisponibilidadCanchaResponse -> DisponibilidadDuracionResponse ->
     * SlotDisponibleResponse) ya es 100% libre/ocupado por slot: no tiene ningún campo de
     * jugador/titular, así que no hace falta filtrar nada acá.
     */
    public DisponibilidadEstablecimientoResponse obtenerDisponibilidad(String slug, LocalDate fecha, LocalDate fechaFin) {
        Establecimiento establecimiento = establecimientoRepository.findBySlugAndIsActiveTrue(slug)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        return disponibilidadService.obtenerDisponibilidad(establecimiento.getId(), fecha, fechaFin);
    }
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "feat: disponibilidad pública por slug (reusa DisponibilidadService)"
```

---

## Task 7: Controller, `permitAll`, and removing the old `/buscar`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoController.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/config/security/SecurityConfig.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/EstablecimientoController.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoServiceTest.java`

**Interfaces:**
- Consumes: `ComplejoPublicoService.buscarComplejos/obtenerDetalle/obtenerDisponibilidad` (Tasks 4-6).
- Produces: `GET /api/v1/publico/complejos`, `GET /api/v1/publico/complejos/{slug}`, `GET /api/v1/publico/complejos/{slug}/disponibilidad` — consumed by Task 8 (end-to-end test).

- [ ] **Step 1: Create `ComplejoPublicoController`**

```java
package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoCardResponse;
import com.matiasmeira.sacaladelangulo.publico.dto.ComplejoDetalleResponse;
import com.matiasmeira.sacaladelangulo.publico.service.ComplejoPublicoService;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Zona pública del marketplace: descubrimiento y comparación de complejos para un
 * visitante anónimo. Ningún endpoint de este controller requiere autenticación (ver
 * SecurityConfig) ni expone duenoId ni datos de jugadores.
 */
@RestController
@RequestMapping("/api/v1/publico/complejos")
@RequiredArgsConstructor
public class ComplejoPublicoController {

    private final ComplejoPublicoService complejoPublicoService;

    @GetMapping
    public ResponseEntity<Page<ComplejoCardResponse>> buscarComplejos(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Double distanciaKm,
            @RequestParam(required = false) Deporte deporte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hora,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(complejoPublicoService.buscarComplejos(lat, lng, distanciaKm, deporte, fecha, hora, pageable));
    }

    @GetMapping("/{slug}")
    public ResponseEntity<ComplejoDetalleResponse> obtenerDetalle(@PathVariable String slug) {
        return ResponseEntity.ok(complejoPublicoService.obtenerDetalle(slug));
    }

    @GetMapping("/{slug}/disponibilidad")
    public ResponseEntity<DisponibilidadEstablecimientoResponse> obtenerDisponibilidad(
            @PathVariable String slug,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin) {
        return ResponseEntity.ok(complejoPublicoService.obtenerDisponibilidad(slug, fecha, fechaFin));
    }
}
```

- [ ] **Step 2: Update `SecurityConfig` — swap the old matcher for the new namespace**

In `SecurityConfig.java`, change:

```java
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/establecimientos/buscar").permitAll()
```

to:

```java
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/publico/**").permitAll()
```

- [ ] **Step 3: Remove the old `/buscar` endpoint from `EstablecimientoController`**

Delete this method entirely from `EstablecimientoController.java`:

```java
    @GetMapping("/buscar")
    public ResponseEntity<List<EstablecimientoResponse>> buscarEstablecimientos(
            @RequestParam Double latitud,
            @RequestParam Double longitud,
            @RequestParam(required = false, defaultValue = "10.0") Double distanciaKm,
            @RequestParam(required = false) Deporte deporte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime hora) {
        List<EstablecimientoResponse> resultados = establecimientoService.buscarEstablecimientos(latitud, longitud, distanciaKm, deporte, fecha, hora);
        return ResponseEntity.ok(resultados);
    }
```

Remove the now-unused imports from the top of the file: `com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte`, `org.springframework.format.annotation.DateTimeFormat`, `java.time.LocalDate`, `java.time.LocalTime`.

- [ ] **Step 4: Remove `buscarEstablecimientos` from `EstablecimientoService`**

Delete the `buscarEstablecimientos` method (including its Javadoc) and the `VENTANA_DISPONIBILIDAD_MINUTOS` constant from `EstablecimientoService.java`. Remove the now-unused fields `canchaRepository` and `reservaRepository` (they were only read inside the method just deleted — confirm with a search before deleting: `grep -n "canchaRepository\|reservaRepository" EstablecimientoService.java` should show 0 remaining usages besides the field declarations themselves).

Remove the now-unused imports: `com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha`, `com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte`, `com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository`, `com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository`, `java.time.LocalDate`, `java.time.LocalDateTime`, `java.time.LocalTime`.

Keep: `DayOfWeek`, `ArrayList`, `HashMap`, `HashSet`, `List`, `Map`, `Set`, `Collectors` — all still used by `validarHorarios`/`mapearHorarios`/`mapearConCalificaciones`.

- [ ] **Step 5: Remove the now-dead test from `EstablecimientoServiceTest`**

Delete the `buscarEstablecimientosSinFechaYHoraDevuelveResultadosCercanos` test method, and remove the `@Mock private CanchaRepository canchaRepository;` and `@Mock private ReservaRepository reservaRepository;` fields (plus their now-unused imports) — `EstablecimientoService` no longer has those constructor dependencies.

- [ ] **Step 6: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS. In particular confirm `EstablecimientoServiceTest`, `EstablecimientoRepositoryTest` (still tests `findCercanosYPorDeporte`, untouched), and everything under `publico` still pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoController.java src/main/java/com/matiasmeira/sacaladelangulo/core/config/security/SecurityConfig.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/EstablecimientoController.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoService.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/EstablecimientoServiceTest.java
git commit -m "feat: expone /api/v1/publico/complejos y elimina el viejo /buscar"
```

---

## Task 8: End-to-end proof — no auth, 404, no PII

**Files:**
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoControllerIntegrationTest.java`

**Interfaces:**
- Consumes: the full Spring context (real `SecurityConfig`, real JPA against an embedded H2, real controllers/services from Tasks 1-7).

- [ ] **Step 1: Write the test**

```java
package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jwt.secret=test-secret-de-al-menos-32-bytes-1234567890"
})
@Transactional
@DisplayName("ComplejoPublicoController - Zona pública sin autenticación (end-to-end)")
class ComplejoPublicoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    private Establecimiento seedComplejoActivo() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-e2e@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo E2E")
                .direccion("Calle E2E 123")
                .slug("complejo-e2e")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento.setHorariosAtencion(new ArrayList<>(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build())));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(6000))
                .build()));
        canchaRepository.save(cancha);

        return establecimiento;
    }

    @Test
    @DisplayName("GET /publico/complejos responde 200 sin Authorization")
    void buscarComplejos_SinAuth_Devuelve200() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} responde 200 sin Authorization y sin duenoId")
    void obtenerDetalle_SinAuth_Devuelve200SinDuenoId() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("complejo-e2e"))
                .andExpect(jsonPath("$.precioDesde").value(6000))
                .andExpect(jsonPath("$.senaDesde").value(1000))
                .andExpect(content().string(not(containsString("duenoId"))));
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con slug inexistente responde 404")
    void obtenerDetalle_SlugInexistente_Devuelve404() throws Exception {
        mockMvc.perform(get("/api/v1/publico/complejos/no-existe"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con complejo inactivo responde 404")
    void obtenerDetalle_ComplejoInactivo_Devuelve404() throws Exception {
        Establecimiento activo = seedComplejoActivo();
        activo.setIsActive(false);
        establecimientoRepository.save(activo);

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug}/disponibilidad responde 200 sin Authorization y sin datos de jugador")
    void obtenerDisponibilidad_SinAuth_Devuelve200SinPii() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e/disponibilidad")
                        .param("fecha", LocalDate.of(2026, 8, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("jugador"))))
                .andExpect(content().string(not(containsString("titular"))));
    }
}
```

- [ ] **Step 2: Run to see it (mostly) fail before this task's prerequisites**

If Tasks 1-7 are already done in order, this should mostly pass on the first run — it's a capstone test, not a driver for new production code. If something fails, it's telling you Tasks 1-7 have a real integration gap (e.g. a filter chain issue `SecurityConfig` alone can't reveal). Diagnose against the actual failure, don't guess.

Run: `./mvnw test -Dtest=ComplejoPublicoControllerIntegrationTest`

- [ ] **Step 3: Fix forward until green**

Common gaps to check if it's red:
- 401/403 instead of 200 → the `SecurityConfig` matcher in Task 7 Step 2 didn't take (check the exact path pattern `/api/v1/publico/**`).
- Context fails to load → a required property is missing; this test only overrides `spring.jpa.hibernate.ddl-auto`, `spring.flyway.enabled`, and `jwt.secret` — every other property already has a default in `application.properties` (confirmed in Task research), so if the context still fails to load, read the actual startup exception rather than adding properties speculatively.

- [ ] **Step 4: Run the full default suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/matiasmeira/sacaladelangulo/publico/controller/ComplejoPublicoControllerIntegrationTest.java
git commit -m "test: prueba end-to-end de la zona pública (sin auth, 404, sin PII)"
```

---

## Task 9: Final full-suite pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full default suite one more time from a clean state**

Run: `./mvnw test`
Expected: BUILD SUCCESS, zero failures, zero errors.

- [ ] **Step 2: Sanity-check the acceptance list from the spec against what was built**

- [ ] Los 3 endpoints (`/publico/complejos`, `/publico/complejos/{slug}`, `/publico/complejos/{slug}/disponibilidad`) responden sin auth → Task 8.
- [ ] El detalle da 404 con slug inexistente/inactivo → Task 5 (unit) + Task 8 (e2e).
- [ ] La disponibilidad pública no filtra PII → Task 6 (by construction, reusing the PII-free `DisponibilidadEstablecimientoResponse` tree) + Task 8 (e2e string check).
- [ ] `precioDesde`/`senaDesde`/`deportes` derivan bien con un complejo de varias canchas → Task 4/5 unit tests.
- [ ] Generación de slug con nombres duplicados no colisiona → Task 3 (`SlugGeneratorTest`).
- [ ] `./mvnw test` pasa → this task.

- [ ] **Step 3: Report status to the user**

No commit for this task — it's verification-only. If anything above is unchecked, go back to the relevant task before declaring the feature done.
