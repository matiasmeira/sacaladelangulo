# Expiración automática de prueba (TRIAL -> FREE) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Degradar automáticamente a FREE a los OWNER que quedaron en TRIAL después de que venció su prueba gratuita (`Usuario.fechaFinPrueba`), algo que hoy no existe: `AvisoFinPruebaService` solo notifica en los umbrales de 7/3/1 día, pero nunca toca `planSuscripcion`.

**Architecture:** Un job nuevo `ExpiracionPruebaService` (`@Scheduled`, cron diario) recorre en lotes paginados a los usuarios `TRIAL` vencidos (`UsuarioRepository`, query nueva) y delega la degradación de CADA usuario a `DegradacionPlanService.degradarPorVencimiento(id)`, un bean aparte con `@Transactional(REQUIRES_NEW)` — mismo motivo que `EmailPendienteRegistro` está separado de `EmailReintentoJob`: Spring no aplica `@Transactional` en self-invocation. El orquestador captura la excepción alrededor de cada llamada, así que un usuario que falla no aborta ni el resto del lote ni el resto de la corrida. Cada degradación exitosa persiste una fila en una tabla de auditoría nueva y dedicada (`auditoria_degradacion_plan`) — **no** se reutiliza `RegistroAuditoria`/`AccionAuditoria` (empleado/model) porque esa tabla exige `establecimiento_id NOT NULL` y un OWNER en TRIAL puede no tener ningún establecimiento todavía; es el mismo problema que ya resolvió `AuditoriaEliminacionUsuario` (V15), y este plan sigue ese mismo precedente. La degradación publica `PruebaVencidaEvent`, consumido por un listener `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` que manda el email (mismo patrón que `AvisoFinPruebaEmailListener`/`CuentaEliminadaEmailListener`), ya pasando por `EmailServiceConReintentos` (pipeline de reintentos automático, sin código nuevo).

**Tech Stack:** Spring Boot 3.5 / Spring Data JPA / Flyway (Postgres) / Lombok / Thymeleaf / JUnit 5 + Mockito / `@DataJpaTest` + H2 para repositorios / `@SpringBootTest` + H2 (`ddl-auto=create-drop`) + `@MockitoBean` para el flujo transaccional completo.

**Spec:** No hay doc de spec separado — el pedido original y los hallazgos del PASO 0 (investigación previa) están resumidos en Global Constraints y en el Architecture de arriba. Decisión confirmada con el usuario: tabla de auditoría propia (no reusar `AccionAuditoria`).

## Global Constraints (hallazgos del PASO 0 — leer antes de implementar)

- **El límite de 3 establecimientos activos (`EstablecimientoService.LIMITE_ESTABLECIMIENTOS_ACTIVOS`) es una constante fija, igual para TRIAL/FREE/PREMIUM.** No depende del plan. Degradar TRIAL→FREE **no** puede dejar a nadie por encima de ningún límite de establecimientos: no hay nada que romper ni que migrar en ese frente.
- Lo único que sí cambia con el plan es `esPlanLimitado(plan) == (plan == FREE)` en `EstablecimientoService`/`CanchaService`: fuerza `requiereSena=true` + una seña mínima, pero **solo hacia adelante** (próxima alta/edición de establecimiento o cancha). No hay que tocar ni migrar datos existentes.
- **Solo los OWNER pasan por TRIAL** (`AuthService`, `fechaFinPrueba = now().plusMonths(1)`). PLAYER y EMPLOYEE nacen en FREE directamente. El job en la práctica solo va a tocar OWNERs.
- Todos los `@Scheduled` del repo (`ReservaExpiracionService`, `IdempotencyCleanupService`, `RateLimiterService`, `EmailReintentoJob`, `AvisoFinPruebaService`) asumen instancia única, sin lock distribuido. Este job sigue el mismo criterio: comentario explícito, sin ShedLock.
- `AccionAuditoria`/`RegistroAuditoria` NO se usa para esta feature (ver Architecture). La tabla `accion` es `VARCHAR(255)` sin CHECK constraint, así que de haberse usado no habría hecho falta migración por eso — pero el problema real es el FK `establecimiento_id NOT NULL`, no la columna.
- `emailService.enviar(...)` (interfaz `EmailService`, implementación `EmailServiceConReintentos`) YA encola automáticamente en `EmailPendiente` si el envío falla. Los listeners nuevos no necesitan ningún código de reintento propio.
- No modificar `AvisoFinPruebaService`.
- Todos los mensajes/copy van en español rioplatense (voseo), mismo tono que el resto de las plantillas de `templates/email/`.
- `./mvnw test` (suite estándar, sin Docker) debe pasar al final del plan (última tarea). Los tests nuevos son `@DataJpaTest`+H2 o `@SpringBootTest`+H2 (`ddl-auto=create-drop`, Flyway apagado) — ninguno requiere Postgres/Testcontainers, mismo criterio que `UsuarioRepositoryFinPruebaTest`/`FotoEstablecimientoServiceFronterasTransaccionalesTest`.
- Migraciones Flyway incrementales: la siguiente libre es **V22** (la última existente es V21).

---

## Task 1: Tabla y entidad de auditoría — `AuditoriaDegradacionPlan` + migración V22

**Files:**
- Create: `src/main/resources/db/migration/V22__auditoria_degradacion_plan.sql`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlan.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaDegradacionPlanRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlanTest.java`

**Interfaces:**
- Produces: `AuditoriaDegradacionPlan` (entidad, tabla `auditoria_degradacion_plan`: `id`, `usuario` [`ManyToOne` `Usuario`, `usuario_id NOT NULL`], `fechaHora` [`LocalDateTime NOT NULL`], `detalle` [`String`, nullable, largo 500]). `AuditoriaDegradacionPlanRepository extends JpaRepository<AuditoriaDegradacionPlan, Long>`. Usado por Task 4.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlanTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-auditoria-degradacion-plan;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("AuditoriaDegradacionPlan - persistencia")
class AuditoriaDegradacionPlanTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @Test
    @DisplayName("guardarYReleer_ConservaUsuarioFechaYDetalle")
    void guardarYReleer_ConservaUsuarioFechaYDetalle() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("degradado@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        AuditoriaDegradacionPlan registro = auditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan.builder()
                .usuario(usuario)
                .fechaHora(ahora)
                .detalle("Prueba vencida")
                .build());

        AuditoriaDegradacionPlan recargado = auditoriaDegradacionPlanRepository.findById(registro.getId()).orElseThrow();

        assertEquals(usuario.getId(), recargado.getUsuario().getId());
        assertEquals(ahora, recargado.getFechaHora());
        assertEquals("Prueba vencida", recargado.getDetalle());
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=AuditoriaDegradacionPlanTest`
Expected: FAIL — no compila (`AuditoriaDegradacionPlan` y `AuditoriaDegradacionPlanRepository` no existen).

- [ ] **Step 3: Crear la migración**

Crear `src/main/resources/db/migration/V22__auditoria_degradacion_plan.sql`:

```sql
-- =============================================================================
-- V22 — Auditoría de degradación de plan (TRIAL vencido -> FREE)
--
-- Tabla nueva y desacoplada de registro_auditoria_empleados (empleado/model): esa tabla
-- exige un establecimiento no-nulo por fila y no encaja acá, porque un OWNER en TRIAL puede
-- no tener ningún establecimiento todavía cuando se le vence la prueba. Mismo criterio que
-- V15 (auditoria_eliminacion_usuario) para el mismo tipo de problema.
-- =============================================================================

CREATE TABLE auditoria_degradacion_plan (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    usuario_id   BIGINT NOT NULL,
    fecha_hora   TIMESTAMP NOT NULL,
    detalle      VARCHAR(500),
    CONSTRAINT fk_auditoria_degradacion_plan_usuario
        FOREIGN KEY (usuario_id) REFERENCES usuarios (id)
);

CREATE INDEX idx_auditoria_degradacion_plan_usuario
    ON auditoria_degradacion_plan (usuario_id);
```

- [ ] **Step 4: Crear la entidad**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlan.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Auditoría de la degradación automática de plan TRIAL -> FREE (ver ExpiracionPruebaService).
 * Entidad propia y desacoplada de RegistroAuditoria (empleado/model): esa tabla exige un
 * Establecimiento no-nulo por fila y no encaja acá, porque un OWNER en TRIAL puede no tener
 * ningún establecimiento todavía cuando se le vence la prueba (mismo motivo que llevó a crear
 * AuditoriaEliminacionUsuario en vez de reusar RegistroAuditoria).
 */
@Entity
@Table(name = "auditoria_degradacion_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaDegradacionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;

    @Column(length = 500)
    private String detalle;
}
```

- [ ] **Step 5: Crear el repositorio**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaDegradacionPlanRepository.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad AuditoriaDegradacionPlan. Solo persistencia, sin queries
 * especiales por ahora.
 */
@Repository
public interface AuditoriaDegradacionPlanRepository extends JpaRepository<AuditoriaDegradacionPlan, Long> {
}
```

- [ ] **Step 6: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=AuditoriaDegradacionPlanTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V22__auditoria_degradacion_plan.sql src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlan.java src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaDegradacionPlanRepository.java src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaDegradacionPlanTest.java
git commit -m "feat(auth): agrega tabla y entidad de auditoría para degradación de plan"
```

---

## Task 2: Query de usuarios TRIAL vencidos en `UsuarioRepository`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepositoryExpiracionPruebaTest.java`

**Interfaces:**
- Produces: `UsuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(PlanSuscripcion planSuscripcion, LocalDateTime ahora, Pageable pageable): Page<Usuario>`. Usado por Task 5.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepositoryExpiracionPruebaTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UsuarioRepository - Finder de expiración de prueba (degradación TRIAL -> FREE)")
class UsuarioRepositoryExpiracionPruebaTest {

    private static final LocalDateTime AHORA = LocalDateTime.now();

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialVencido_LoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialVencido_LoDevuelve() {
        Usuario vencido = entityManager.persist(usuarioDePrueba(
                "vencido@test.com", PlanSuscripcion.TRIAL, AHORA.minusDays(1), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(1, resultado.getTotalElements());
        assertEquals(vencido.getId(), resultado.getContent().get(0).getId());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialNoVencido_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialNoVencido_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("no-vencido@test.com", PlanSuscripcion.TRIAL, AHORA.plusDays(5), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_YaEnFreeOPremium_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_YaEnFreeOPremium_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("ya-free@test.com", PlanSuscripcion.FREE, AHORA.minusDays(1), null));
        entityManager.persist(usuarioDePrueba("ya-premium@test.com", PlanSuscripcion.PREMIUM, AHORA.minusDays(1), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_CuentaEliminada_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_CuentaEliminada_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba(
                "eliminado@test.com", PlanSuscripcion.TRIAL, AHORA.minusDays(1), AHORA.minusHours(1)));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_FechaFinPruebaNula_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_FechaFinPruebaNula_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("sin-fecha@test.com", PlanSuscripcion.TRIAL, null, null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    private Usuario usuarioDePrueba(String email, PlanSuscripcion plan, LocalDateTime fechaFinPrueba, LocalDateTime deletedAt) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario de prueba")
                .rol(Role.OWNER)
                .planSuscripcion(plan)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(fechaFinPrueba)
                .deletedAt(deletedAt)
                .build();
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=UsuarioRepositoryExpiracionPruebaTest`
Expected: FAIL — no compila (el método `findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull` no existe en `UsuarioRepository`).

- [ ] **Step 3: Agregar el método al repositorio**

En `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepository.java`, agregar el import `com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion` y, después del último finder de fin de prueba (línea 56), agregar:

```java
    /**
     * Usuarios OWNER en TRIAL cuya prueba gratuita ya venció (ver ExpiracionPruebaService,
     * degradación automática a FREE). AndDeletedAtIsNull por el mismo motivo que los finders
     * de aviso de arriba: no tocar una cuenta ya eliminada. Paginado porque, a diferencia del
     * rango acotado a un día calendario de los finders de aviso, acá no hay tope natural al
     * volumen (todo TRIAL vencido hasta la fecha, sin importar desde cuándo).
     */
    Page<Usuario> findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
            PlanSuscripcion planSuscripcion, LocalDateTime ahora, Pageable pageable);
```

- [ ] **Step 4: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=UsuarioRepositoryExpiracionPruebaTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepository.java src/test/java/com/matiasmeira/sacaladelangulo/auth/repository/UsuarioRepositoryExpiracionPruebaTest.java
git commit -m "feat(auth): agrega finder paginado de usuarios TRIAL con prueba vencida"
```

---

## Task 3: `PruebaVencidaEvent`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEvent.java`

**Interfaces:**
- Produces: `record PruebaVencidaEvent(Long usuarioId)`. Usado por Task 4 (publica) y Task 6 (consume).

No hay test dedicado: es un record sin lógica, igual que `AvisoFinPruebaEvent`/`CuentaEliminadaEvent` (tampoco tienen test propio); se ejercita indirectamente en los tests de Task 4 y Task 6.

- [ ] **Step 1: Crear el record**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEvent.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Publicado por DegradacionPlanService cuando un usuario pasa de TRIAL a FREE por
 * vencimiento de la prueba gratuita. Lleva solo el ID porque el listener corre @Async en un
 * hilo/persistence-context distinto al de la transacción que lo publica (mismo motivo que
 * AvisoFinPruebaEvent).
 */
public record PruebaVencidaEvent(Long usuarioId) {
}
```

- [ ] **Step 2: Compilar**

Run: `./mvnw compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEvent.java
git commit -m "feat(auth): agrega PruebaVencidaEvent"
```

---

## Task 4: `DegradacionPlanService` — degradación de un usuario, transacción propia

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanService.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanServiceTest.java`

**Interfaces:**
- Consumes: `UsuarioRepository.findById(Long): Optional<Usuario>` / `.save(Usuario)`; `AuditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan)` (Task 1); `ApplicationEventPublisher.publishEvent(Object)`; `PruebaVencidaEvent` (Task 3).
- Produces: `DegradacionPlanService.degradarPorVencimiento(Long usuarioId): void`, `@Transactional(REQUIRES_NEW)`. Usado por Task 5.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DegradacionPlanService - Degradación TRIAL -> FREE por usuario")
class DegradacionPlanServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private DegradacionPlanService service;

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioTrialVencido_PasaAFreeAuditaYPublicaEvento")
    void degradarPorVencimiento_UsuarioTrialVencido_PasaAFreeAuditaYPublicaEvento() {
        Usuario usuario = usuarioDePrueba(1L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(1L);

        assertEquals(PlanSuscripcion.FREE, usuario.getPlanSuscripcion());
        verify(usuarioRepository).save(usuario);

        ArgumentCaptor<AuditoriaDegradacionPlan> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaDegradacionPlan.class);
        verify(auditoriaDegradacionPlanRepository).save(auditoriaCaptor.capture());
        assertEquals(usuario, auditoriaCaptor.getValue().getUsuario());

        ArgumentCaptor<PruebaVencidaEvent> eventoCaptor = ArgumentCaptor.forClass(PruebaVencidaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals(1L, eventoCaptor.getValue().usuarioId());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioYaEnFree_NoHaceNada")
    void degradarPorVencimiento_UsuarioYaEnFree_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(2L, PlanSuscripcion.FREE, null);
        when(usuarioRepository.findById(2L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(2L);

        verify(usuarioRepository, never()).save(any());
        verify(auditoriaDegradacionPlanRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioYaEnPremium_NoHaceNada")
    void degradarPorVencimiento_UsuarioYaEnPremium_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(3L, PlanSuscripcion.PREMIUM, null);
        when(usuarioRepository.findById(3L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(3L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_CuentaEliminada_NoHaceNada")
    void degradarPorVencimiento_CuentaEliminada_NoHaceNada() {
        Usuario usuario = usuarioDePrueba(4L, PlanSuscripcion.TRIAL, LocalDateTime.now());
        when(usuarioRepository.findById(4L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(4L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_UsuarioNoEncontrado_NoHaceNada")
    void degradarPorVencimiento_UsuarioNoEncontrado_NoHaceNada() {
        when(usuarioRepository.findById(5L)).thenReturn(Optional.empty());

        service.degradarPorVencimiento(5L);

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("degradarPorVencimiento_DosVecesSeguidas_LaSegundaEsNoOp")
    void degradarPorVencimiento_DosVecesSeguidas_LaSegundaEsNoOp() {
        Usuario usuario = usuarioDePrueba(6L, PlanSuscripcion.TRIAL, null);
        when(usuarioRepository.findById(6L)).thenReturn(Optional.of(usuario));

        service.degradarPorVencimiento(6L);
        service.degradarPorVencimiento(6L);

        verify(usuarioRepository, times(1)).save(any());
        verify(eventPublisher, times(1)).publishEvent(any());
    }

    private Usuario usuarioDePrueba(Long id, PlanSuscripcion plan, LocalDateTime deletedAt) {
        Usuario usuario = Usuario.builder()
                .email("usuario" + id + "@test.com")
                .password("hash")
                .nombre("Usuario " + id)
                .planSuscripcion(plan)
                .fechaFinPrueba(LocalDateTime.now().minusDays(1))
                .deletedAt(deletedAt)
                .build();
        usuario.setId(id);
        return usuario;
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=DegradacionPlanServiceTest`
Expected: FAIL — no compila (`DegradacionPlanService` no existe).

- [ ] **Step 3: Implementar el servicio**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanService.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaDegradacionPlan;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Degrada UN usuario de TRIAL a FREE, en su propia transacción. Bean aparte de
 * ExpiracionPruebaService (que orquesta el recorrido paginado) por el mismo motivo que
 * EmailPendienteRegistro está separado de EmailReintentoJob: Spring no aplica @Transactional
 * en self-invocation, así que llamar a este método como método privado del propio
 * orquestador silenciosamente no abriría ninguna transacción.
 *
 * <p>REQUIRES_NEW: cada usuario se procesa en su propia transacción corta, así que si uno
 * falla, el resto del lote sigue procesándose sin arrastrar el error — ExpiracionPruebaService
 * captura la excepción por usuario y continúa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradacionPlanService {

    private final UsuarioRepository usuarioRepository;
    private final AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void degradarPorVencimiento(Long usuarioId) {
        Usuario usuario = usuarioRepository.findById(usuarioId).orElse(null);
        if (usuario == null) {
            log.warn("No se encontró el usuario {} al intentar degradar su plan vencido", usuarioId);
            return;
        }

        // Defensivo: si ya no está en TRIAL o la cuenta se eliminó entre que se armó el lote
        // y esta transacción, no hay nada que hacer (idempotente).
        if (usuario.getPlanSuscripcion() != PlanSuscripcion.TRIAL || usuario.getDeletedAt() != null) {
            return;
        }

        LocalDateTime fechaFinPrueba = usuario.getFechaFinPrueba();
        usuario.setPlanSuscripcion(PlanSuscripcion.FREE);
        usuarioRepository.save(usuario);

        auditoriaDegradacionPlanRepository.save(AuditoriaDegradacionPlan.builder()
                .usuario(usuario)
                .fechaHora(LocalDateTime.now())
                .detalle("Prueba vencida el " + fechaFinPrueba + ". Plan degradado de TRIAL a FREE.")
                .build());

        eventPublisher.publishEvent(new PruebaVencidaEvent(usuario.getId()));
        log.info("Usuario {} degradado de TRIAL a FREE por vencimiento de prueba", usuario.getId());
    }
}
```

- [ ] **Step 4: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=DegradacionPlanServiceTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanService.java src/test/java/com/matiasmeira/sacaladelangulo/auth/service/DegradacionPlanServiceTest.java
git commit -m "feat(auth): agrega DegradacionPlanService para degradar TRIAL vencido a FREE"
```

---

## Task 5: `ExpiracionPruebaService` — job paginado + properties

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceTest.java`

**Interfaces:**
- Consumes: `UsuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(...)` (Task 2); `DegradacionPlanService.degradarPorVencimiento(Long)` (Task 4).
- Produces: `ExpiracionPruebaService.degradarPruebasVencidas(): void`, `@Scheduled(cron = "${app.suscripcion.expiracion-cron:0 0 4 * * *}")`. Properties nuevas: `app.suscripcion.expiracion-cron`, `app.suscripcion.expiracion-lote`.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExpiracionPruebaService - Recorrido paginado de degradación")
class ExpiracionPruebaServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private DegradacionPlanService degradacionPlanService;

    @InjectMocks
    private ExpiracionPruebaService service;

    @Test
    @DisplayName("degradarPruebasVencidas_SinUsuariosVencidos_NoLlamaAlDegradador")
    void degradarPruebasVencidas_SinUsuariosVencidos_NoLlamaAlDegradador() {
        ReflectionTestUtils.setField(service, "tamanioLote", 100);
        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.degradarPruebasVencidas();

        verify(degradacionPlanService, never()).degradarPorVencimiento(anyLong());
    }

    @Test
    @DisplayName("degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_ProcesaTodosEnVariasPaginas")
    void degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_ProcesaTodosEnVariasPaginas() {
        ReflectionTestUtils.setField(service, "tamanioLote", 2);

        Usuario u1 = usuarioDePrueba(1L);
        Usuario u2 = usuarioDePrueba(2L);
        Usuario u3 = usuarioDePrueba(3L);

        Page<Usuario> primeraPagina = new PageImpl<>(List.of(u1, u2), Pageable.ofSize(2), 3);
        Page<Usuario> segundaPagina = new PageImpl<>(List.of(u3), Pageable.ofSize(2), 3);

        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(primeraPagina)
                .thenReturn(segundaPagina)
                .thenReturn(Page.empty());

        service.degradarPruebasVencidas();

        verify(degradacionPlanService).degradarPorVencimiento(1L);
        verify(degradacionPlanService).degradarPorVencimiento(2L);
        verify(degradacionPlanService).degradarPorVencimiento(3L);
    }

    @Test
    @DisplayName("degradarPruebasVencidas_UnUsuarioFalla_SigueProcesandoAlRestoDelLote")
    void degradarPruebasVencidas_UnUsuarioFalla_SigueProcesandoAlRestoDelLote() {
        ReflectionTestUtils.setField(service, "tamanioLote", 100);

        Usuario u1 = usuarioDePrueba(1L);
        Usuario u2 = usuarioDePrueba(2L);
        Usuario u3 = usuarioDePrueba(3L);

        Page<Usuario> pagina = new PageImpl<>(List.of(u1, u2, u3), Pageable.ofSize(100), 3);

        when(usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                eq(PlanSuscripcion.TRIAL), any(), any(Pageable.class)))
                .thenReturn(pagina)
                .thenReturn(Page.empty());

        doThrow(new RuntimeException("fallo simulado")).when(degradacionPlanService).degradarPorVencimiento(2L);

        service.degradarPruebasVencidas();

        verify(degradacionPlanService, times(1)).degradarPorVencimiento(1L);
        verify(degradacionPlanService, times(1)).degradarPorVencimiento(2L);
        verify(degradacionPlanService, times(1)).degradarPorVencimiento(3L);
    }

    private Usuario usuarioDePrueba(Long id) {
        Usuario usuario = Usuario.builder()
                .email("usuario" + id + "@test.com")
                .password("hash")
                .nombre("Usuario " + id)
                .build();
        usuario.setId(id);
        return usuario;
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=ExpiracionPruebaServiceTest`
Expected: FAIL — no compila (`ExpiracionPruebaService` no existe).

- [ ] **Step 3: Implementar el servicio**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaService.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Degrada automáticamente a FREE a los usuarios que quedaron en TRIAL después de que venció
 * su prueba gratuita (ver Usuario.fechaFinPrueba): hoy AvisoFinPruebaService solo notifica en
 * los umbrales de 7/3/1 día, pero nunca toca planSuscripcion, así que un dueño que no elige
 * plan pago se queda en TRIAL para siempre.
 *
 * <p>De instancia única, igual que ReservaExpiracionService, el rate limiter y
 * EmailReintentoJob: si en el futuro se escala horizontalmente, esto necesita un lock
 * compartido (ShedLock) para que dos instancias no procesen el mismo usuario dos veces.
 *
 * <p>NO es @Transactional: cada usuario se degrada en su propia transacción a través de
 * DegradacionPlanService.degradarPorVencimiento (bean aparte, REQUIRES_NEW), así que un
 * usuario que falla no aborta ni el resto del lote ni el resto de la corrida — se loguea y
 * se sigue. Vuelve a pedir la página 0 después de cada lote (en vez de avanzar con
 * page.next()) porque cada usuario procesado deja de matchear el filtro planSuscripcion=TRIAL,
 * así que el resultado se va achicando solo hasta vaciarse.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiracionPruebaService {

    private final UsuarioRepository usuarioRepository;
    private final DegradacionPlanService degradacionPlanService;

    @Value("${app.suscripcion.expiracion-lote:100}")
    private int tamanioLote;

    @Scheduled(cron = "${app.suscripcion.expiracion-cron:0 0 4 * * *}")
    public void degradarPruebasVencidas() {
        Pageable pageable = PageRequest.of(0, tamanioLote);
        int totalDegradados = 0;
        int totalFallidos = 0;

        Page<Usuario> pagina = buscarVencidos(pageable);
        while (!pagina.isEmpty()) {
            for (Usuario usuario : pagina.getContent()) {
                try {
                    degradacionPlanService.degradarPorVencimiento(usuario.getId());
                    totalDegradados++;
                } catch (RuntimeException ex) {
                    totalFallidos++;
                    log.error("No se pudo degradar al usuario {} de TRIAL a FREE", usuario.getId(), ex);
                }
            }
            pagina = buscarVencidos(pageable);
        }

        if (totalDegradados > 0 || totalFallidos > 0) {
            log.info("Degradación de pruebas vencidas finalizada. Degradados: {}, fallidos: {}",
                    totalDegradados, totalFallidos);
        }
    }

    private Page<Usuario> buscarVencidos(Pageable pageable) {
        return usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, LocalDateTime.now(), pageable);
    }
}
```

- [ ] **Step 4: Agregar las properties**

En `src/main/resources/application.properties`, después del bloque `app.mail.marketing-from` (línea 113), agregar:

```properties

# Degradación automática de plan al vencer la prueba gratuita (ver ExpiracionPruebaService):
# un OWNER que se queda en TRIAL después de fechaFinPrueba pasa a FREE. Cron diario en
# horario de baja carga (4am) para no competir con tráfico real; tamaño de lote acotado para
# no cargar toda la tabla de usuarios en memoria en una corrida con muchos vencidos.
app.suscripcion.expiracion-cron=${SUSCRIPCION_EXPIRACION_CRON:0 0 4 * * *}
app.suscripcion.expiracion-lote=${SUSCRIPCION_EXPIRACION_LOTE:100}
```

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=ExpiracionPruebaServiceTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaService.java src/main/resources/application.properties src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceTest.java
git commit -m "feat(auth): agrega ExpiracionPruebaService, job diario de degradación TRIAL vencido"
```

---

## Task 6: Email de aviso — plantilla + listener AFTER_COMMIT

**Files:**
- Create: `src/main/resources/templates/email/prueba-vencida.html`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListener.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListenerTest.java`

**Interfaces:**
- Consumes: `PruebaVencidaEvent` (Task 3); `UsuarioRepository.findById`; `EmailRenderer.render(String, Map)`; `EmailService.enviar(String, String, String)`.
- Produces: `PruebaVencidaEmailListener.enviarAvisoDeDegradacion(PruebaVencidaEvent): void`, `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListenerTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PruebaVencidaEmailListener - Envío del email de degradación de plan")
class PruebaVencidaEmailListenerTest {

    private static final String FRONTEND_URL = "http://localhost:5173";

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmailRenderer emailRenderer;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private PruebaVencidaEmailListener listener;

    @Test
    @DisplayName("enviarAvisoDeDegradacion_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail")
    void enviarAvisoDeDegradacion_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail() {
        ReflectionTestUtils.setField(listener, "frontendUrl", FRONTEND_URL);

        Usuario usuario = Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .build();
        usuario.setId(10L);
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(usuario));
        when(emailRenderer.render(eq("prueba-vencida"), anyMap())).thenReturn("<html>prueba-vencida</html>");

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(10L));

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("prueba-vencida"), modeloCaptor.capture());
        assertEquals("Carlos", modeloCaptor.getValue().get("nombre"));
        assertEquals(FRONTEND_URL + "/panel/configuracion", modeloCaptor.getValue().get("ctaUrl"));

        verify(emailService).enviar(eq("dueno@test.com"), eq("Tu prueba gratuita terminó"), eq("<html>prueba-vencida</html>"));
    }

    @Test
    @DisplayName("enviarAvisoDeDegradacion_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion")
    void enviarAvisoDeDegradacion_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(99L));

        verify(emailService, never()).enviar(anyString(), anyString(), anyString());
        verifyNoInteractions(emailRenderer);
    }

    @Test
    @DisplayName("enviarAvisoDeDegradacion_CuentaEliminada_NoEnviaEmail")
    void enviarAvisoDeDegradacion_CuentaEliminada_NoEnviaEmail() {
        Usuario usuario = Usuario.builder()
                .email("deleted+11@saque.deleted")
                .password("hash")
                .nombre("Usuario eliminado")
                .deletedAt(LocalDateTime.now())
                .build();
        usuario.setId(11L);
        when(usuarioRepository.findById(11L)).thenReturn(Optional.of(usuario));

        listener.enviarAvisoDeDegradacion(new PruebaVencidaEvent(11L));

        verify(emailService, never()).enviar(anyString(), anyString(), anyString());
        verifyNoInteractions(emailRenderer);
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=PruebaVencidaEmailListenerTest`
Expected: FAIL — no compila (`PruebaVencidaEmailListener` no existe).

- [ ] **Step 3: Crear la plantilla**

Crear `src/main/resources/templates/email/prueba-vencida.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <title>Tu prueba gratuita terminó</title>
</head>
<body>

<div th:replace="~{layout :: email(titulo='Tu prueba gratuita terminó', contenidoHtml=~{::#contenido})}">

    <div id="contenido">
        <p style="margin:0 0 16px 0;">
            Hola <span th:text="${nombre}">Dueño</span>, tu período de prueba gratuita en Saque
            terminó y tu cuenta pasó al plan gratuito (FREE).
        </p>
        <p style="margin:0 0 24px 0;">
            Con el plan FREE seguís administrando tus canchas y reservas, pero vas a necesitar
            configurar una seña obligatoria mínima al crear o editar tus establecimientos y
            canchas. Si querés recuperar todas las funciones sin esa restricción, pasate a
            PREMIUM cuando quieras.
        </p>

        <table role="presentation" cellpadding="0" cellspacing="0" border="0">
            <tr>
                <td style="border-radius:10px;background-color:#0e56c9;">
                    <a th:href="${ctaUrl}" href="#"
                       style="display:inline-block;padding:14px 28px;font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:10px;">
                        Ver planes
                    </a>
                </td>
            </tr>
        </table>
    </div>

</div>

</body>
</html>
```

- [ ] **Step 4: Implementar el listener**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListener.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía el email de aviso de degradación de plan (TRIAL vencido -> FREE). AFTER_COMMIT +
 * @Async por el mismo motivo que AvisoFinPruebaEmailListener: el cambio de planSuscripcion ya
 * quedó persistido antes de intentar el envío, y no se retiene la conexión de base durante la
 * latencia de una llamada de red externa. Re-fetch de la entidad porque @Async corre en un
 * hilo/persistence-context distinto al de la transacción original.
 *
 * <p>Chequea deletedAt explícitamente (no solo que el usuario exista): entre que se publica
 * el evento y que este listener corre, la cuenta pudo haberse eliminado/anonimizado, y un
 * envío a esa altura pegaría contra el placeholder @saque.deleted (mismo criterio que
 * UsuarioRepository.findByFechaFinPruebaBetween...AndDeletedAtIsNull).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PruebaVencidaEmailListener {

    private static final String ASUNTO = "Tu prueba gratuita terminó";

    private final UsuarioRepository usuarioRepository;
    private final EmailRenderer emailRenderer;
    private final EmailService emailService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarAvisoDeDegradacion(PruebaVencidaEvent evento) {
        Usuario usuario = usuarioRepository.findById(evento.usuarioId()).orElse(null);
        if (usuario == null || usuario.getDeletedAt() != null) {
            log.warn("No se envía el aviso de degradación de plan para el usuario {}: no existe o la cuenta fue eliminada",
                    evento.usuarioId());
            return;
        }

        String html = emailRenderer.render("prueba-vencida", Map.of(
                "nombre", usuario.getNombre(),
                "ctaUrl", frontendUrl + "/panel/configuracion"
        ));

        emailService.enviar(usuario.getEmail(), ASUNTO, html);
    }
}
```

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=PruebaVencidaEmailListenerTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/email/prueba-vencida.html src/main/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListener.java src/test/java/com/matiasmeira/sacaladelangulo/auth/service/PruebaVencidaEmailListenerTest.java
git commit -m "feat(auth): agrega email de aviso de degradación de plan (AFTER_COMMIT)"
```

---

## Task 7: Test de integración — flujo completo, AFTER_COMMIT y establecimientos intactos

**Files:**
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ExpiracionPruebaService` (Task 5), `UsuarioRepository`, `EstablecimientoRepository`, `AuditoriaDegradacionPlanRepository` (Task 1), `EmailService` (mockeado vía `@MockitoBean`).

- [ ] **Step 1: Escribir el test**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceIntegrationTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaDegradacionPlanRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Prueba el flujo completo de ExpiracionPruebaService contra un Spring context real: que el
 * cambio de plan se persista, que la auditoría quede registrada, que el email de aviso salga
 * recién AFTER_COMMIT (nunca con una transacción de base todavía abierta) y que los
 * establecimientos del usuario NO se toquen. Necesita un contenedor real de Spring, no
 * new ExpiracionPruebaService(...): @TransactionalEventListener(AFTER_COMMIT) sólo dispara a
 * través de un commit real gestionado por el contenedor (mismo motivo que
 * FotoEstablecimientoServiceFronterasTransaccionalesTest).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-expiracion-prueba;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "app.suscripcion.expiracion-lote=2"
})
@DisplayName("ExpiracionPruebaService - Flujo completo de degradación TRIAL -> FREE")
class ExpiracionPruebaServiceIntegrationTest {

    @Autowired
    private ExpiracionPruebaService expiracionPruebaService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private AuditoriaDegradacionPlanRepository auditoriaDegradacionPlanRepository;

    @MockitoBean
    private EmailService emailService;

    @Test
    @DisplayName("degradarPruebasVencidas_UsuarioTrialVencido_PasaAFreeAuditaYMandaElEmailSoloAfterCommit")
    void degradarPruebasVencidas_UsuarioTrialVencido_PasaAFreeAuditaYMandaElEmailSoloAfterCommit() {
        Usuario dueno = usuarioRepository.save(usuarioTrialVencido("dueno-vencido@test.com"));

        AtomicBoolean transaccionActivaAlEnviarEmail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transaccionActivaAlEnviarEmail.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(emailService).enviar(any(), any(), any());

        expiracionPruebaService.degradarPruebasVencidas();

        verify(emailService, timeout(2000)).enviar(eq(dueno.getEmail()), any(), any());
        assertThat(transaccionActivaAlEnviarEmail.get())
                .as("el email debe salir solo después del commit, sin una transacción de base todavía abierta")
                .isFalse();

        Usuario recargado = usuarioRepository.findById(dueno.getId()).orElseThrow();
        assertThat(recargado.getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);

        assertThat(auditoriaDegradacionPlanRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("degradarPruebasVencidas_DuenoConEstablecimientoActivo_NoLoDesactiva")
    void degradarPruebasVencidas_DuenoConEstablecimientoActivo_NoLoDesactiva() {
        Usuario dueno = usuarioRepository.save(usuarioTrialVencido("dueno-con-complejo@test.com"));
        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle 1")
                .slug("complejo-expiracion-test")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        expiracionPruebaService.degradarPruebasVencidas();

        verify(emailService, timeout(2000)).enviar(eq(dueno.getEmail()), any(), any());

        Establecimiento recargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertThat(recargado.getIsActive()).isTrue();
    }

    @Test
    @DisplayName("degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_DegradaATodos")
    void degradarPruebasVencidas_MasUsuariosQueElTamanioDeLote_DegradaATodos() {
        Usuario u1 = usuarioRepository.save(usuarioTrialVencido("u1@test.com"));
        Usuario u2 = usuarioRepository.save(usuarioTrialVencido("u2@test.com"));
        Usuario u3 = usuarioRepository.save(usuarioTrialVencido("u3@test.com"));

        expiracionPruebaService.degradarPruebasVencidas();

        assertThat(usuarioRepository.findById(u1.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
        assertThat(usuarioRepository.findById(u2.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
        assertThat(usuarioRepository.findById(u3.getId()).orElseThrow().getPlanSuscripcion()).isEqualTo(PlanSuscripcion.FREE);
    }

    private Usuario usuarioTrialVencido(String email) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueño de prueba")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(LocalDateTime.now().minusDays(1))
                .build();
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=ExpiracionPruebaServiceIntegrationTest`
Expected: PASS (3 tests). Si el primer test es flaky por timing del hilo `@Async`, subir el timeout de `timeout(2000)` a `timeout(5000)` — no cambiar el diseño del listener para "arreglarlo".

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/matiasmeira/sacaladelangulo/auth/service/ExpiracionPruebaServiceIntegrationTest.java
git commit -m "test(auth): agrega test de integración del flujo de expiración de prueba"
```

---

## Task 8: Suite completa

**Files:** ninguno (solo verificación).

- [ ] **Step 1: Correr toda la suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, sin regresiones en los tests existentes (incluye `AvisoFinPruebaServiceTest` intacto, ver Global Constraints).

- [ ] **Step 2: Reportar el resultado**

Resumir cuántos tests corrieron/pasaron y pegar cualquier fallo tal cual lo imprime Maven (no parafrasear stack traces).
