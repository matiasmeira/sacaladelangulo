# Eliminación de cuenta de usuario — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Agregar soft-delete + anonimización de cuenta de usuario (self-delete para PLAYER/OWNER, admin-delete para cualquier cuenta salvo EMPLOYEE), preservando integridad referencial en reservas/auditoría.

**Architecture:** Un campo nuevo `Usuario.deletedAt` es el discriminador real de "eliminado" (no se reutiliza `isActive`, que ya está sobrecargado). Un servicio central `UsuarioEliminacionService` concentra guardrails, cancelación de reservas activas, anonimización de PII y auditoría (en una entidad nueva y desacoplada del sistema de auditoría existente, que exige `Establecimiento` no-nulo). Dos endpoints delgados (`DELETE /api/v1/usuarios/me`, `DELETE /api/v1/admin/usuarios/{id}`) delegan a ese servicio. Se cierran además tres vías de reingreso que dependían de efectos colaterales (login, recuperación de contraseña) o que podían generar bounces de email contra el placeholder `@saque.deleted`.

**Tech Stack:** Spring Boot 3 / Spring Data JPA / Spring Security / Flyway (Postgres) / Lombok / JUnit 5 + Mockito / MockMvc + H2 para tests de integración.

**Spec:** `docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md`

## Global Constraints

- Todos los mensajes de error y de UI van en español, mismo tono que el resto del código (ver excepciones existentes: `JugadorBloqueadoException`, `TokenInvalidoException`, etc.).
- Ninguna excepción de negocio nueva usa `@PreAuthorize`: la autorización de rol se valida a mano dentro del service (mismo patrón que `OfertaMarketingService`, `AutorizacionEmpleadoService`), y se mapea en `GlobalExceptionHandler`.
- Es soft-delete + anonimización, nunca hard delete: ninguna tarea de este plan borra una fila de `usuarios` ni de `reservas`.
- Las migraciones Flyway son incrementales: no se reescribe ni se borra ninguna migración existente (V1–V13).
- `./mvnw test` debe pasar al final del plan (última tarea).

---

## Task 1: `Usuario.deletedAt` + migración V14

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/Usuario.java`
- Create: `src/main/resources/db/migration/V14__usuario_deleted_at.sql`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/UsuarioDeletedAtTest.java`

**Interfaces:**
- Produces: `Usuario.getDeletedAt(): LocalDateTime` / `Usuario.setDeletedAt(LocalDateTime)` (vía Lombok `@Getter`/`@Setter`/`@Builder`, ya presentes en la clase). Toda tarea posterior que necesite marcar o consultar una cuenta eliminada usa este campo.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/UsuarioDeletedAtTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-deleted-at;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Usuario.deletedAt - persistencia")
class UsuarioDeletedAtTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("guardarConDeletedAt_SeRecuperaElMismoValorAlReleer")
    void guardarConDeletedAt_SeRecuperaElMismoValorAlReleer() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("deleted-at-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .deletedAt(ahora)
                .build());

        Usuario recargado = usuarioRepository.findById(usuario.getId()).orElseThrow();

        assertEquals(ahora, recargado.getDeletedAt());
    }

    @Test
    @DisplayName("guardarSinDeletedAt_QuedaNull")
    void guardarSinDeletedAt_QuedaNull() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("sin-deleted-at-test@test.com")
                .password("hash")
                .nombre("Usuario Activo")
                .rol(Role.PLAYER)
                .isActive(true)
                .build());

        Usuario recargado = usuarioRepository.findById(usuario.getId()).orElseThrow();

        assertNull(recargado.getDeletedAt());
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=UsuarioDeletedAtTest`
Expected: FAIL — no compila (`Usuario` no tiene `deletedAt`/`getDeletedAt`/builder `.deletedAt(...)`).

- [ ] **Step 3: Agregar el campo a la entidad**

En `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/Usuario.java`, agregar después del campo `tokenVersion` (línea 100-102) y antes de `establecimiento`:

```java
    /**
     * Momento en que se dio de baja la cuenta (soft-delete + anonimización). Discriminador
     * real de "cuenta eliminada": isActive por sí solo no alcanza, porque ya se reutiliza
     * para "onboarding no completado" en PLAYER y para desactivación de EMPLOYEE.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
```

- [ ] **Step 4: Crear la migración**

Crear `src/main/resources/db/migration/V14__usuario_deleted_at.sql`:

```sql
-- =============================================================================
-- V14 — Columna deleted_at en usuarios (baja de cuenta)
--
-- Discriminador real de una cuenta eliminada. isActive=false por sí solo no
-- alcanza: hoy ya se usa para "onboarding no completado" (PLAYER) y para
-- desactivación de EMPLOYEE (ver EmpleadoService.desactivarEmpleado), así que no
-- puede reutilizarse como flag de "eliminado". Ver spec de eliminación de cuenta
-- (docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md).
-- =============================================================================

ALTER TABLE usuarios ADD COLUMN deleted_at TIMESTAMP NULL;
```

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=UsuarioDeletedAtTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/model/Usuario.java \
        src/main/resources/db/migration/V14__usuario_deleted_at.sql \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/model/UsuarioDeletedAtTest.java
git commit -m "feat: agrega Usuario.deletedAt como discriminador de cuenta eliminada"
```

---

## Task 2: Entidad de auditoría de eliminación + migración V15

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/TipoEliminacionCuenta.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuario.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaEliminacionUsuarioRepository.java`
- Create: `src/main/resources/db/migration/V15__auditoria_eliminacion_usuario.sql`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuarioTest.java`

**Interfaces:**
- Consumes: `Usuario` (Task 1).
- Produces: `AuditoriaEliminacionUsuario` (campos `id`, `usuario`, `actorId`, `tipo`, `detalle`, `fechaHora`, con builder Lombok), `TipoEliminacionCuenta.AUTOELIMINACION` / `.ELIMINACION_ADMIN`, `AuditoriaEliminacionUsuarioRepository.save(AuditoriaEliminacionUsuario)`. Usado por `UsuarioEliminacionService` (Task 7/8).

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuarioTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-auditoria-eliminacion;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("AuditoriaEliminacionUsuario - persistencia")
class AuditoriaEliminacionUsuarioTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;

    @Test
    @DisplayName("guardarConActorId_SeRecuperaTipoYDetalleAlReleer")
    void guardarConActorId_SeRecuperaTipoYDetalleAlReleer() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("auditoria-eliminacion-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .build());

        AuditoriaEliminacionUsuario registro = auditoriaEliminacionUsuarioRepository.save(
                AuditoriaEliminacionUsuario.builder()
                        .usuario(usuario)
                        .actorId(99L)
                        .tipo(TipoEliminacionCuenta.ELIMINACION_ADMIN)
                        .detalle("Forzado: 1 establecimiento(s) activo(s) sin desactivar")
                        .fechaHora(LocalDateTime.now())
                        .build());

        AuditoriaEliminacionUsuario recargado = auditoriaEliminacionUsuarioRepository.findById(registro.getId()).orElseThrow();

        assertEquals(usuario.getId(), recargado.getUsuario().getId());
        assertEquals(99L, recargado.getActorId());
        assertEquals(TipoEliminacionCuenta.ELIMINACION_ADMIN, recargado.getTipo());
        assertEquals("Forzado: 1 establecimiento(s) activo(s) sin desactivar", recargado.getDetalle());
    }

    @Test
    @DisplayName("guardarSinActorId_QuedaNull")
    void guardarSinActorId_QuedaNull() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("auditoria-eliminacion-self-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .build());

        AuditoriaEliminacionUsuario registro = auditoriaEliminacionUsuarioRepository.save(
                AuditoriaEliminacionUsuario.builder()
                        .usuario(usuario)
                        .tipo(TipoEliminacionCuenta.AUTOELIMINACION)
                        .fechaHora(LocalDateTime.now())
                        .build());

        AuditoriaEliminacionUsuario recargado = auditoriaEliminacionUsuarioRepository.findById(registro.getId()).orElseThrow();

        assertNull(recargado.getActorId());
        assertNull(recargado.getDetalle());
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=AuditoriaEliminacionUsuarioTest`
Expected: FAIL — no compila (ninguna de las clases existe todavía).

- [ ] **Step 3: Crear el enum**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/TipoEliminacionCuenta.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

/**
 * Distingue si una baja de cuenta la ejecutó el propio usuario o un ADMIN (ver
 * UsuarioEliminacionService).
 */
public enum TipoEliminacionCuenta {
    AUTOELIMINACION,
    ELIMINACION_ADMIN
}
```

- [ ] **Step 4: Crear la entidad**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuario.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Auditoría de la baja de una cuenta (self-delete o admin-delete). Entidad propia y
 * desacoplada de RegistroAuditoria (empleado/model): esa tabla exige un Establecimiento
 * no-nulo por fila y no encaja para la baja de un PLAYER o de un OWNER sin
 * establecimientos (ver spec de eliminación de cuenta).
 */
@Entity
@Table(name = "auditoria_eliminacion_usuario")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditoriaEliminacionUsuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * La cuenta eliminada (fila ya anonimizada al momento de guardar este registro; el
     * FK sigue siendo válido porque el soft-delete nunca borra la fila).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    /**
     * Null en autoeliminación. Id del ADMIN que ejecutó la baja en eliminación admin.
     */
    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoEliminacionCuenta tipo;

    /**
     * Nullable. Se usa, por ejemplo, para dejar trazado cuántos establecimientos activos
     * tenía un OWNER cuando un ADMIN fuerza la baja salteando ese guardrail.
     */
    @Column(length = 500)
    private String detalle;

    @Column(name = "fecha_hora", nullable = false)
    private LocalDateTime fechaHora;
}
```

- [ ] **Step 5: Crear el repositorio**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaEliminacionUsuarioRepository.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JPA para la entidad AuditoriaEliminacionUsuario. Solo persistencia, sin
 * queries especiales por ahora.
 */
@Repository
public interface AuditoriaEliminacionUsuarioRepository extends JpaRepository<AuditoriaEliminacionUsuario, Long> {
}
```

- [ ] **Step 6: Crear la migración**

Crear `src/main/resources/db/migration/V15__auditoria_eliminacion_usuario.sql`:

```sql
-- =============================================================================
-- V15 — Auditoría de eliminación de cuenta (baja de usuario)
--
-- Tabla nueva y desacoplada de registro_auditoria_empleados (V-anteriores): esa
-- tabla exige un establecimiento no-nulo por fila (está pensada para acciones de
-- dueño/empleado dentro de un establecimiento) y no encaja para la baja de un
-- PLAYER o de un OWNER sin establecimientos. Ver spec de eliminación de cuenta
-- (docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md).
-- =============================================================================

CREATE TABLE auditoria_eliminacion_usuario (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    usuario_id   BIGINT NOT NULL,
    actor_id     BIGINT,
    tipo         VARCHAR(30) NOT NULL,
    detalle      VARCHAR(500),
    fecha_hora   TIMESTAMP NOT NULL,
    CONSTRAINT fk_auditoria_eliminacion_usuario_usuario
        FOREIGN KEY (usuario_id) REFERENCES usuarios (id)
);

CREATE INDEX idx_auditoria_eliminacion_usuario_usuario
    ON auditoria_eliminacion_usuario (usuario_id);
```

- [ ] **Step 7: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=AuditoriaEliminacionUsuarioTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/model/TipoEliminacionCuenta.java \
        src/main/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuario.java \
        src/main/java/com/matiasmeira/sacaladelangulo/auth/repository/AuditoriaEliminacionUsuarioRepository.java \
        src/main/resources/db/migration/V15__auditoria_eliminacion_usuario.sql \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/model/AuditoriaEliminacionUsuarioTest.java
git commit -m "feat: agrega entidad de auditoria para la baja de cuentas"
```

---

## Task 3: `EstablecimientosActivosException` + mapeo 400

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/EstablecimientosActivosException.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java:44-48`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `EstablecimientosActivosException(String message)` (extends `RuntimeException`), mapeada a HTTP 400. Usada por `UsuarioEliminacionService` (Task 7/8) para el guardrail de OWNER.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `src/test/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandlerTest.java`, dentro de la clase existente (mismo patrón que los demás métodos de ese archivo, ya leído: `new GlobalExceptionHandler()` sin Spring context):

```java
    @Test
    @DisplayName("handleEstablecimientosActivosException_Devuelve400ConElMensaje")
    void handleEstablecimientosActivosException_Devuelve400ConElMensaje() {
        EstablecimientosActivosException ex =
                new EstablecimientosActivosException("Desactivá o transferí tus complejos antes de eliminar la cuenta");

        ResponseEntity<Map<String, String>> response = handler.handleEstablecimientosActivosException(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Desactivá o transferí tus complejos antes de eliminar la cuenta", response.getBody().get("error"));
    }
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — no compila (`EstablecimientosActivosException` no existe, `handler.handleEstablecimientosActivosException` no existe).

- [ ] **Step 3: Crear la excepción**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/EstablecimientosActivosException.java`:

```java
package com.matiasmeira.sacaladelangulo.core.exception;

/**
 * Excepción de negocio para el guardrail de baja de cuenta: un OWNER (o el ADMIN que
 * intenta eliminarlo sin forzar) no puede eliminar la cuenta mientras tenga
 * establecimientos activos. Se mapea a HTTP 400.
 */
public class EstablecimientosActivosException extends RuntimeException {

    public EstablecimientosActivosException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Mapear la excepción en el handler**

En `src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java`, agregar el import y el método justo después de `handleIllegalArgumentException` (línea 48):

```java
    @ExceptionHandler(EstablecimientosActivosException.class)
    public ResponseEntity<Map<String, String>> handleEstablecimientosActivosException(EstablecimientosActivosException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
```

(No hace falta import adicional: `EstablecimientosActivosException` queda en el mismo paquete `core.exception` que `GlobalExceptionHandler`.)

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/core/exception/EstablecimientosActivosException.java \
        src/main/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandler.java \
        src/test/java/com/matiasmeira/sacaladelangulo/core/exception/GlobalExceptionHandlerTest.java
git commit -m "feat: agrega EstablecimientosActivosException para el guardrail de baja de OWNER"
```

---

## Task 4: Login rechaza explícitamente cuentas con `deletedAt`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapper.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapperTest.java`

**Interfaces:**
- Consumes: `Usuario.getDeletedAt()` (Task 1).
- Produces: sin cambio de firma — `UsuarioUserDetailsMapper.map(Usuario): UserDetails` sigue igual, pero `isEnabled()` del `UsuarioPrincipal` resultante ahora también depende de `deletedAt`.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapperTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UsuarioUserDetailsMapper - isEnabled")
class UsuarioUserDetailsMapperTest {

    @Test
    @DisplayName("map_IsActiveTrueSinDeletedAt_QuedaHabilitado")
    void map_IsActiveTrueSinDeletedAt_QuedaHabilitado() {
        Usuario usuario = Usuario.builder()
                .email("activo@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(true)
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertTrue(principal.isEnabled());
    }

    @Test
    @DisplayName("map_IsActiveFalse_QuedaDeshabilitado")
    void map_IsActiveFalse_QuedaDeshabilitado() {
        Usuario usuario = Usuario.builder()
                .email("inactivo@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(false)
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertFalse(principal.isEnabled());
    }

    @Test
    @DisplayName("map_IsActiveTrueConDeletedAt_QuedaDeshabilitado")
    void map_IsActiveTrueConDeletedAt_QuedaDeshabilitado() {
        // Estado que no debería darse en producción (isActive se pone en false al anonimizar),
        // pero es justamente el caso que el chequeo explícito de deletedAt cubre: si algún
        // flujo futuro reactivara isActive sin tocar deletedAt, el login sigue bloqueado.
        Usuario usuario = Usuario.builder()
                .email("reactivado@test.com")
                .password("hash")
                .rol(Role.PLAYER)
                .isActive(true)
                .deletedAt(LocalDateTime.now())
                .build();

        UserDetails principal = UsuarioUserDetailsMapper.map(usuario);

        assertFalse(principal.isEnabled());
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=UsuarioUserDetailsMapperTest`
Expected: FAIL en `map_IsActiveTrueConDeletedAt_QuedaDeshabilitado` (hoy `isEnabled()` da `true`, porque solo mira `isActive`).

- [ ] **Step 3: Ajustar el mapper**

En `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapper.java`, reemplazar el método `map`:

```java
    public static UserDetails map(Usuario usuario) {
        boolean enabled = Boolean.TRUE.equals(usuario.getIsActive()) && usuario.getDeletedAt() == null;
        return new UsuarioPrincipal(
                usuario.getEmail(),
                usuario.getPassword(),
                enabled,
                List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getRol().name())),
                usuario.getTokenVersion() == null ? 0 : usuario.getTokenVersion()
        );
    }
```

- [ ] **Step 4: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=UsuarioUserDetailsMapperTest`
Expected: PASS (los 3 casos)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapper.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioUserDetailsMapperTest.java
git commit -m "fix: el login chequea deletedAt explicitamente, no solo isActive"
```

---

## Task 5: `ReservaNotificacionListener` no manda mail a cuentas eliminadas

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListener.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListenerTest.java`

**Interfaces:**
- Consumes: `Usuario.getDeletedAt()` (Task 1).
- Produces: sin cambio de firma pública — el guard `puedeNotificar(Usuario)` es privado, interno a la clase.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListenerTest.java` (dentro de la clase existente, reusando el `dueno`/`establecimiento`/`cancha` de `setUp()`, mismo patrón que los tests ya existentes en ese archivo):

```java
    @Test
    @DisplayName("enviarNotificacionesConfirmacion_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno")
    void enviarNotificacionesConfirmacion_JugadorEliminado_NoEnviaMailAlJugadorPeroSiAlDueno() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(52L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(52L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-nueva-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesConfirmacion(new ReservaConfirmadaEvent(52L));

        verify(emailService, never()).enviar(eq("deleted+1@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-confirmada"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Nueva reserva confirmada en tu establecimiento"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorElJugadorYaEliminado_NoEnviaReciboAlJugadorPeroSiLiberacionAlDueno")
    void enviarNotificacionesCancelacion_CanceladaPorElJugadorYaEliminado_NoEnviaReciboAlJugadorPeroSiLiberacionAlDueno() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(53L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.valueOf(500))
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(53L)).thenReturn(Optional.of(reserva));
        when(emailRenderer.render(eq("reserva-liberada-dueno"), anyMap())).thenReturn("<html>dueno</html>");

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(53L, 1L));

        verify(emailService, never()).enviar(eq("deleted+1@saque.deleted"), any(), any());
        verify(emailRenderer, never()).render(eq("reserva-cancelada-jugador"), anyMap());
        verify(emailService).enviar(eq("dueno@test.com"), eq("Se liberó una cancha"), eq("<html>dueno</html>"));
    }

    @Test
    @DisplayName("enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorEliminado_NoEnviaNingunMailAlJugador")
    void enviarNotificacionesCancelacion_CanceladaPorDuenoConJugadorEliminado_NoEnviaNingunMailAlJugador() {
        Usuario jugadorEliminado = Usuario.builder()
                .id(1L)
                .email("deleted+1@saque.deleted")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .deletedAt(LocalDateTime.now())
                .build();

        Reserva reserva = Reserva.builder()
                .id(54L)
                .jugador(jugadorEliminado)
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        when(reservaRepository.findByIdConEstablecimientoYDueno(54L)).thenReturn(Optional.of(reserva));

        listener.enviarNotificacionesCancelacion(new ReservaCanceladaEvent(54L, 2L));

        verify(emailService, never()).enviar(any(), any(), any());
        verify(emailRenderer, never()).render(any(), anyMap());
    }
```

- [ ] **Step 2: Correr los tests y confirmar que fallan**

Run: `./mvnw test -Dtest=ReservaNotificacionListenerTest`
Expected: FAIL en los 3 tests nuevos — hoy el listener intenta mandarle mail al placeholder `deleted+1@saque.deleted` porque solo chequea `StringUtils.hasText(...)`.

- [ ] **Step 3: Agregar el guard**

En `src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListener.java`:

1. Reemplazar, en `enviarNotificacionesConfirmacion` (línea 60-64):

```java
        Usuario jugador = reserva.getJugador();
        if (puedeNotificar(jugador)) {
            String htmlJugador = emailRenderer.render("reserva-confirmada", modelo);
            emailService.enviar(jugador.getEmail(), ASUNTO_JUGADOR, htmlJugador);
        }
```

2. Reemplazar, en `enviarNotificacionesCancelacion`, la rama `esElJugador` (línea 86-91):

```java
        if (esElJugador) {
            Usuario jugador = reserva.getJugador();
            if (puedeNotificar(jugador)) {
                String htmlJugador = emailRenderer.render("reserva-cancelada-jugador", modelo);
                emailService.enviar(jugador.getEmail(), ASUNTO_CANCELACION_JUGADOR, htmlJugador);
            }
```

3. Reemplazar, en la misma rama `else` de `enviarNotificacionesCancelacion` (línea 96-98):

```java
        } else {
            Usuario jugador = reserva.getJugador();
            if (puedeNotificar(jugador)) {
```

4. Agregar el método privado al final de la clase, antes de `construirModeloBase`:

```java
    /**
     * Corta el envío si el jugador ya fue anonimizado (ver UsuarioEliminacionService): su
     * email pasa a ser un placeholder @saque.deleted que no existe, y un bounce contra un
     * dominio inexistente pega directo contra la reputación de envío del dominio real en
     * Resend.
     */
    private boolean puedeNotificar(Usuario usuario) {
        return usuario != null && usuario.getDeletedAt() == null && StringUtils.hasText(usuario.getEmail());
    }

```

- [ ] **Step 4: Correr los tests y confirmar que pasan**

Run: `./mvnw test -Dtest=ReservaNotificacionListenerTest`
Expected: PASS (todos, incluidos los ya existentes — el guard no cambia el comportamiento para jugadores no eliminados)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListener.java \
        src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaNotificacionListenerTest.java
git commit -m "fix: no envia mail de reserva a jugadores con cuenta eliminada (evita bounces)"
```

---

## Task 6: `RecuperacionPasswordService` rechaza explícitamente cuentas eliminadas

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordServiceTest.java`

**Interfaces:**
- Consumes: `Usuario.getDeletedAt()` (Task 1).
- Produces: sin cambio de firma pública en `solicitarRecuperacion`/`resetPassword`.

**Nota de diseño (corrige una imprecisión del spec):** el chequeo es solo sobre `deletedAt`, **no** sobre `isActive`. `isActive=false` también ocurre para un PLAYER que todavía no verificó el teléfono (onboarding en curso, ver `Usuario.prePersist`) — ese usuario sí debe poder recuperar su contraseña. Si este chequeo mirara `isActive`, rompería la recuperación de contraseña para cualquier cuenta nueva sin verificar.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordServiceTest.java` (dentro de la clase existente):

```java
    @Test
    @DisplayName("solicitarRecuperacion_CuentaEliminada_NoHaceNadaYNoLanzaExcepcion")
    void solicitarRecuperacion_CuentaEliminada_NoHaceNadaYNoLanzaExcepcion() {
        SolicitarRecuperacionPasswordRequest request = new SolicitarRecuperacionPasswordRequest("eliminado@test.com");
        Usuario usuarioEliminado = Usuario.builder()
                .id(1L)
                .email("eliminado@test.com")
                .deletedAt(LocalDateTime.now())
                .build();
        when(usuarioRepository.findByEmail("eliminado@test.com")).thenReturn(Optional.of(usuarioEliminado));

        recuperacionPasswordService.solicitarRecuperacion(request);

        verify(tokenRecuperacionPasswordRepository, never()).deleteByEmail(anyString());
        verify(tokenRecuperacionPasswordRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("resetPassword_Fallo_UsuarioYaEliminado")
    void resetPassword_Fallo_UsuarioYaEliminado() {
        TokenRecuperacionPassword token = TokenRecuperacionPassword.builder()
                .id(1L)
                .email("eliminado@test.com")
                .tokenHash(TokenHasher.sha256Hex("token-valido"))
                .codigoHash(TokenHasher.sha256Hex("123456"))
                .intentos(0)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(10))
                .build();
        Usuario usuarioEliminado = Usuario.builder()
                .id(1L)
                .email("eliminado@test.com")
                .password("hash-random")
                .tokenVersion(5)
                .deletedAt(LocalDateTime.now().minusDays(1))
                .build();
        ResetPasswordRequest request = new ResetPasswordRequest("token-valido", null, null, "NuevaPass123");

        when(tokenRecuperacionPasswordRepository.findByTokenHash(TokenHasher.sha256Hex("token-valido"))).thenReturn(Optional.of(token));
        when(usuarioRepository.findByEmail("eliminado@test.com")).thenReturn(Optional.of(usuarioEliminado));

        assertThrows(TokenInvalidoException.class, () -> recuperacionPasswordService.resetPassword(request));

        verify(usuarioRepository, never()).save(any());
        verify(tokenRecuperacionPasswordRepository, never()).delete(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
```

- [ ] **Step 2: Correr los tests y confirmar que fallan**

Run: `./mvnw test -Dtest=RecuperacionPasswordServiceTest`
Expected: FAIL en los 2 tests nuevos — hoy `solicitarRecuperacion` solo mira si el email existe (encuentra la fila igual, aunque esté anonimizada) y `resetPassword` no chequea `deletedAt` en absoluto.

- [ ] **Step 3: Ajustar `solicitarRecuperacion`**

En `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordService.java`, reemplazar (línea 86-89):

```java
        Optional<Usuario> usuarioEncontrado = usuarioRepository.findByEmail(email);
        if (usuarioEncontrado.isEmpty() || usuarioEncontrado.get().getDeletedAt() != null) {
            log.info("Solicitud de recuperación de contraseña para email no registrado o cuenta eliminada");
            return;
        }
```

(Agregar `import java.util.Optional;` si no está ya importado — no lo está en este archivo hoy.)

- [ ] **Step 4: Ajustar `resetPassword`**

En el mismo archivo, dentro de `resetPassword` (línea 121-123), después de resolver el `Usuario`:

```java
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuario.getDeletedAt() != null) {
            throw new TokenInvalidoException("El token de recuperación no es válido");
        }

        usuario.setPassword(passwordEncoder.encode(request.nuevaPassword()));
```

- [ ] **Step 5: Correr los tests y confirmar que pasan**

Run: `./mvnw test -Dtest=RecuperacionPasswordServiceTest`
Expected: PASS (todos, incluidos los ya existentes)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordService.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/service/RecuperacionPasswordServiceTest.java
git commit -m "fix: la recuperacion de password rechaza explicitamente cuentas eliminadas"
```

---

## Task 7: `UsuarioEliminacionService` — autoeliminación (self-delete)

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEvent.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionService.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java`

**Interfaces:**
- Consumes: `Usuario.getDeletedAt()`/`setDeletedAt()` (Task 1); `AuditoriaEliminacionUsuario`, `TipoEliminacionCuenta`, `AuditoriaEliminacionUsuarioRepository` (Task 2); `EstablecimientosActivosException` (Task 3); `EstablecimientoRepository.findByDuenoIdAndIsActiveTrue(Long): List<Establecimiento>` (ya existente); `ReservaCanceladaEvent(Long reservaId, Long actorId)` (ya existente).
- Produces: `UsuarioEliminacionService.autoeliminar(String email, String password): void` — usado por `UsuarioController` (Task 9). `CuentaEliminadaEvent(String email, String nombre)` — consumido por `CuentaEliminadaEmailListener` (Task 11). `ReservaRepository.findByJugadorIdAndEstadoIn(Long jugadorId, List<EstadoReserva> estados): List<Reserva>` — nuevo método derivado, reusable por cualquier otro caso que necesite las reservas activas de un jugador.

- [ ] **Step 1: Escribir los tests que fallan**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEvent.java` (necesario para que el test de abajo compile):

```java
package com.matiasmeira.sacaladelangulo.auth.service;

/**
 * Se publica al confirmar la baja de una cuenta (self o admin), con el email y el
 * nombre reales capturados ANTES de anonimizar (valores planos, no una referencia a la
 * entidad ya anonimizada), para el mail de confirmación (ver CuentaEliminadaEmailListener).
 */
public record CuentaEliminadaEvent(String email, String nombre) {
}
```

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TipoEliminacionCuenta;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EstablecimientosActivosException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaCanceladaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioEliminacionService - Baja de cuenta (soft-delete + anonimizacion)")
class UsuarioEliminacionServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UsuarioEliminacionService usuarioEliminacionService;

    private Usuario jugador;

    @BeforeEach
    void setUp() {
        jugador = Usuario.builder()
                .id(1L)
                .email("jugador@test.com")
                .nombre("Juan Jugador")
                .telefono("11122233")
                .password("hash-viejo")
                .rol(Role.PLAYER)
                .isActive(true)
                .tokenVersion(2)
                .aceptaMarketing(true)
                .build();
    }

    @Test
    @DisplayName("autoeliminar_PasswordCorrecta_AnonimizaUsuarioYPublicaEventoDeConfirmacion")
    void autoeliminar_PasswordCorrecta_AnonimizaUsuarioYPublicaEventoDeConfirmacion() {
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(reservaRepository.findByJugadorIdAndEstadoIn(eq(1L), any())).thenReturn(List.of());

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        ArgumentCaptor<Usuario> usuarioCaptor = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarioRepository).save(usuarioCaptor.capture());
        Usuario guardado = usuarioCaptor.getValue();
        assertEquals("deleted+1@saque.deleted", guardado.getEmail());
        assertEquals("Usuario eliminado", guardado.getNombre());
        assertNull(guardado.getTelefono());
        assertEquals("hash-random", guardado.getPassword());
        assertEquals(false, guardado.getAceptaMarketing());
        assertEquals(false, guardado.getIsActive());
        assertNotNull(guardado.getDeletedAt());
        assertEquals(3, guardado.getTokenVersion());

        ArgumentCaptor<AuditoriaEliminacionUsuario> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaEliminacionUsuario.class);
        verify(auditoriaEliminacionUsuarioRepository).save(auditoriaCaptor.capture());
        assertEquals(TipoEliminacionCuenta.AUTOELIMINACION, auditoriaCaptor.getValue().getTipo());
        assertEquals(jugador, auditoriaCaptor.getValue().getUsuario());
        assertNull(auditoriaCaptor.getValue().getActorId());

        ArgumentCaptor<CuentaEliminadaEvent> eventoCaptor = ArgumentCaptor.forClass(CuentaEliminadaEvent.class);
        verify(eventPublisher).publishEvent(eventoCaptor.capture());
        assertEquals("jugador@test.com", eventoCaptor.getValue().email());
        assertEquals("Juan Jugador", eventoCaptor.getValue().nombre());
    }

    @Test
    @DisplayName("autoeliminar_PasswordIncorrecta_LanzaBadCredentialsExceptionYNoModificaNada")
    void autoeliminar_PasswordIncorrecta_LanzaBadCredentialsExceptionYNoModificaNada() {
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Incorrecta", "hash-viejo")).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "Incorrecta"));

        verify(usuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("autoeliminar_RolEmployee_LanzaAccessDeniedException")
    void autoeliminar_RolEmployee_LanzaAccessDeniedException() {
        jugador.setRol(Role.EMPLOYEE);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        assertThrows(AccessDeniedException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_RolAdmin_LanzaAccessDeniedException")
    void autoeliminar_RolAdmin_LanzaAccessDeniedException() {
        jugador.setRol(Role.ADMIN);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        assertThrows(AccessDeniedException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_OwnerConEstablecimientosActivos_LanzaEstablecimientosActivosException")
    void autoeliminar_OwnerConEstablecimientosActivos_LanzaEstablecimientosActivosException() {
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L))
                .thenReturn(List.of(Establecimiento.builder().id(10L).build()));

        assertThrows(EstablecimientosActivosException.class,
                () -> usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123"));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("autoeliminar_OwnerSinEstablecimientosActivos_Anonimiza")
    void autoeliminar_OwnerSinEstablecimientosActivos_Anonimiza() {
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L)).thenReturn(List.of());
        when(reservaRepository.findByJugadorIdAndEstadoIn(eq(1L), any())).thenReturn(List.of());

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        verify(usuarioRepository).save(any(Usuario.class));
    }

    @Test
    @DisplayName("autoeliminar_ConReservasFuturasActivas_LasCancelaYPublicaUnEventoPorCadaUna")
    void autoeliminar_ConReservasFuturasActivas_LasCancelaYPublicaUnEventoPorCadaUna() {
        Reserva reservaConfirmada = Reserva.builder().id(100L).jugador(jugador).estado(EstadoReserva.CONFIRMADA).build();
        Reserva reservaPendiente = Reserva.builder().id(101L).jugador(jugador).estado(EstadoReserva.PENDIENTE_SENA).build();

        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));
        when(passwordEncoder.matches("Password123", "hash-viejo")).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(reservaRepository.findByJugadorIdAndEstadoIn(eq(1L), any()))
                .thenReturn(List.of(reservaConfirmada, reservaPendiente));

        usuarioEliminacionService.autoeliminar("jugador@test.com", "Password123");

        assertEquals(EstadoReserva.CANCELADA, reservaConfirmada.getEstado());
        assertEquals(EstadoReserva.CANCELADA, reservaPendiente.getEstado());
        verify(reservaRepository).save(reservaConfirmada);
        verify(reservaRepository).save(reservaPendiente);
        verify(eventPublisher).publishEvent(new ReservaCanceladaEvent(100L, 1L));
        verify(eventPublisher).publishEvent(new ReservaCanceladaEvent(101L, 1L));
    }

    @Test
    @DisplayName("autoeliminar_CuentaYaEliminada_NoRepiteNingunEfecto")
    void autoeliminar_CuentaYaEliminada_NoRepiteNingunEfecto() {
        jugador.setDeletedAt(LocalDateTime.now().minusDays(1));
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(jugador));

        usuarioEliminacionService.autoeliminar("jugador@test.com", "cualquiera");

        verify(usuarioRepository, never()).save(any());
        verify(auditoriaEliminacionUsuarioRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(passwordEncoder, never()).matches(any(), any());
    }
}
```

- [ ] **Step 2: Correr los tests y confirmar que fallan**

Run: `./mvnw test -Dtest=UsuarioEliminacionServiceTest`
Expected: FAIL — no compila (`UsuarioEliminacionService` no existe, `ReservaRepository.findByJugadorIdAndEstadoIn` no existe).

- [ ] **Step 3: Agregar el método al repositorio de reservas**

En `src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java`, agregar (por ejemplo, junto a `findByJugadorId`, línea 122-123):

```java
    /**
     * Reservas activas (no finalizadas/canceladas) de un jugador, sin paginar: usado por
     * UsuarioEliminacionService para cancelarlas todas al eliminar la cuenta. Sin filtro de
     * fecha adicional: CONFIRMADA/PENDIENTE_SENA ya implican "todavía no jugada" en este
     * dominio (lo pasado transiciona a FINALIZADA/AUSENTE).
     */
    List<Reserva> findByJugadorIdAndEstadoIn(Long jugadorId, List<EstadoReserva> estados);
```

- [ ] **Step 4: Escribir `UsuarioEliminacionService`**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionService.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.AuditoriaEliminacionUsuario;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TipoEliminacionCuenta;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.core.exception.EstablecimientosActivosException;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.service.ReservaCanceladaEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Baja de cuenta: soft-delete + anonimización de PII, preservando integridad referencial
 * (reservas y auditoría siguen apuntando a la fila, ya anonimizada). Ver spec en
 * docs/superpowers/specs/2026-08-15-eliminacion-cuenta-design.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UsuarioEliminacionService {

    private static final List<EstadoReserva> ESTADOS_A_CANCELAR = List.of(EstadoReserva.CONFIRMADA, EstadoReserva.PENDIENTE_SENA);

    private final UsuarioRepository usuarioRepository;
    private final EstablecimientoRepository establecimientoRepository;
    private final ReservaRepository reservaRepository;
    private final AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Autoeliminación: solo PLAYER/OWNER, requiere la contraseña actual. EMPLOYEE lo
     * gestiona el dueño (ver EmpleadoService); ADMIN queda fuera de alcance.
     */
    public void autoeliminar(String email, String password) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuario.getDeletedAt() != null) {
            return;
        }

        if (usuario.getRol() == Role.EMPLOYEE || usuario.getRol() == Role.ADMIN) {
            throw new AccessDeniedException("Este endpoint no está disponible para tu rol");
        }

        if (!passwordEncoder.matches(password, usuario.getPassword())) {
            throw new BadCredentialsException("Credenciales inválidas");
        }

        eliminar(usuario, null, TipoEliminacionCuenta.AUTOELIMINACION, false);
    }

    /**
     * Baja por ADMIN: cualquier cuenta salvo EMPLOYEE (tiene su propio ciclo de vida vía
     * EmpleadoService). No pide la contraseña del target. forzar=true saltea el guardrail
     * de OWNER con establecimientos activos, sin cascadear (quedan activos, ahora bajo el
     * dueño anonimizado).
     */
    public void eliminarComoAdmin(String emailAdmin, Long usuarioId, boolean forzar) {
        Usuario admin = usuarioRepository.findByEmail(emailAdmin)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (admin.getRol() != Role.ADMIN) {
            throw new AccessDeniedException("No está autorizado para eliminar cuentas");
        }

        Usuario usuario = usuarioRepository.findById(usuarioId)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));

        if (usuario.getDeletedAt() != null) {
            return;
        }

        if (usuario.getRol() == Role.EMPLOYEE) {
            throw new IllegalArgumentException("Los empleados se gestionan desde el establecimiento");
        }

        eliminar(usuario, admin.getId(), TipoEliminacionCuenta.ELIMINACION_ADMIN, forzar);
    }

    private void eliminar(Usuario usuario, Long actorId, TipoEliminacionCuenta tipo, boolean forzar) {
        String detalleAuditoria = null;
        if (usuario.getRol() == Role.OWNER) {
            List<Establecimiento> activos = establecimientoRepository.findByDuenoIdAndIsActiveTrue(usuario.getId());
            if (!activos.isEmpty()) {
                if (!forzar) {
                    throw new EstablecimientosActivosException(
                            "Desactivá o transferí tus complejos antes de eliminar la cuenta");
                }
                detalleAuditoria = "Forzado: " + activos.size() + " establecimiento(s) activo(s) sin desactivar";
                log.warn("Eliminación forzada de OWNER {} con {} establecimiento(s) activo(s)",
                        usuario.getId(), activos.size());
            }
        }

        String emailReal = usuario.getEmail();
        String nombreReal = usuario.getNombre();

        List<Reserva> reservasActivas = reservaRepository.findByJugadorIdAndEstadoIn(usuario.getId(), ESTADOS_A_CANCELAR);
        for (Reserva reserva : reservasActivas) {
            reserva.setEstado(EstadoReserva.CANCELADA);
            reservaRepository.save(reserva);
            eventPublisher.publishEvent(new ReservaCanceladaEvent(reserva.getId(), usuario.getId()));
        }

        usuario.setEmail("deleted+" + usuario.getId() + "@saque.deleted");
        usuario.setNombre("Usuario eliminado");
        usuario.setTelefono(null);
        usuario.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        usuario.setAceptaMarketing(false);
        usuario.setIsActive(false);
        usuario.setDeletedAt(LocalDateTime.now());
        usuario.setTokenVersion(usuario.getTokenVersion() + 1);
        usuarioRepository.save(usuario);

        auditoriaEliminacionUsuarioRepository.save(AuditoriaEliminacionUsuario.builder()
                .usuario(usuario)
                .actorId(actorId)
                .tipo(tipo)
                .detalle(detalleAuditoria)
                .fechaHora(LocalDateTime.now())
                .build());

        eventPublisher.publishEvent(new CuentaEliminadaEvent(emailReal, nombreReal));

        log.info("Cuenta {} eliminada ({})", usuario.getId(), tipo);
    }
}
```

- [ ] **Step 5: Correr los tests y confirmar que pasan**

Run: `./mvnw test -Dtest=UsuarioEliminacionServiceTest`
Expected: PASS (los 8 tests de autoeliminación; `eliminarComoAdmin` todavía no tiene tests, se agregan en el Task 8)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEvent.java \
        src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionService.java \
        src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java
git commit -m "feat: agrega UsuarioEliminacionService con la autoeliminacion (self-delete)"
```

---

## Task 8: `UsuarioEliminacionService` — eliminación por ADMIN

**Files:**
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java`
- Modify (solo si el Step 2 encuentra una discrepancia): `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionService.java`

**Interfaces:**
- Consumes: `UsuarioEliminacionService.eliminarComoAdmin(String, Long, boolean)` — el método ya existe, implementado en Task 7 junto con `autoeliminar`, porque ambos comparten el método privado `eliminar(...)`. Este task agrega su cobertura de tests; no debería requerir tocar producción salvo que el Step 2 encuentre una discrepancia real con el comportamiento descripto.

- [ ] **Step 1: Escribir los tests que fallan**

Agregar a `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java` (dentro de la clase existente):

```java
    @Test
    @DisplayName("eliminarComoAdmin_ActorNoAdmin_LanzaAccessDeniedException")
    void eliminarComoAdmin_ActorNoAdmin_LanzaAccessDeniedException() {
        Usuario noAdmin = Usuario.builder().id(2L).email("owner@test.com").rol(Role.OWNER).build();
        when(usuarioRepository.findByEmail("owner@test.com")).thenReturn(Optional.of(noAdmin));

        assertThrows(AccessDeniedException.class,
                () -> usuarioEliminacionService.eliminarComoAdmin("owner@test.com", 1L, false));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarComoAdmin_TargetEmployee_LanzaIllegalArgumentException")
    void eliminarComoAdmin_TargetEmployee_LanzaIllegalArgumentException() {
        Usuario admin = Usuario.builder().id(9L).email("admin@test.com").rol(Role.ADMIN).build();
        jugador.setRol(Role.EMPLOYEE);
        when(usuarioRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(jugador));

        assertThrows(IllegalArgumentException.class,
                () -> usuarioEliminacionService.eliminarComoAdmin("admin@test.com", 1L, false));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarComoAdmin_TargetOwnerConEstablecimientosActivosSinForzar_LanzaExcepcion")
    void eliminarComoAdmin_TargetOwnerConEstablecimientosActivosSinForzar_LanzaExcepcion() {
        Usuario admin = Usuario.builder().id(9L).email("admin@test.com").rol(Role.ADMIN).build();
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(jugador));
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L))
                .thenReturn(List.of(Establecimiento.builder().id(10L).build()));

        assertThrows(EstablecimientosActivosException.class,
                () -> usuarioEliminacionService.eliminarComoAdmin("admin@test.com", 1L, false));

        verify(usuarioRepository, never()).save(any());
    }

    @Test
    @DisplayName("eliminarComoAdmin_TargetOwnerConEstablecimientosActivosForzando_AnonimizaYRegistraDetalleEnAuditoria")
    void eliminarComoAdmin_TargetOwnerConEstablecimientosActivosForzando_AnonimizaYRegistraDetalleEnAuditoria() {
        Usuario admin = Usuario.builder().id(9L).email("admin@test.com").rol(Role.ADMIN).build();
        jugador.setRol(Role.OWNER);
        when(usuarioRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(jugador));
        when(passwordEncoder.encode(anyString())).thenReturn("hash-random");
        when(establecimientoRepository.findByDuenoIdAndIsActiveTrue(1L)).thenReturn(
                List.of(Establecimiento.builder().id(10L).build(), Establecimiento.builder().id(11L).build()));
        when(reservaRepository.findByJugadorIdAndEstadoIn(eq(1L), any())).thenReturn(List.of());

        usuarioEliminacionService.eliminarComoAdmin("admin@test.com", 1L, true);

        verify(usuarioRepository).save(any(Usuario.class));
        verify(passwordEncoder, never()).matches(any(), any());

        ArgumentCaptor<AuditoriaEliminacionUsuario> auditoriaCaptor = ArgumentCaptor.forClass(AuditoriaEliminacionUsuario.class);
        verify(auditoriaEliminacionUsuarioRepository).save(auditoriaCaptor.capture());
        assertEquals(TipoEliminacionCuenta.ELIMINACION_ADMIN, auditoriaCaptor.getValue().getTipo());
        assertEquals(9L, auditoriaCaptor.getValue().getActorId());
        assertEquals("Forzado: 2 establecimiento(s) activo(s) sin desactivar", auditoriaCaptor.getValue().getDetalle());
    }

    @Test
    @DisplayName("eliminarComoAdmin_TargetYaEliminado_NoRepiteNingunEfecto")
    void eliminarComoAdmin_TargetYaEliminado_NoRepiteNingunEfecto() {
        Usuario admin = Usuario.builder().id(9L).email("admin@test.com").rol(Role.ADMIN).build();
        jugador.setDeletedAt(LocalDateTime.now().minusDays(1));
        when(usuarioRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(admin));
        when(usuarioRepository.findById(1L)).thenReturn(Optional.of(jugador));

        usuarioEliminacionService.eliminarComoAdmin("admin@test.com", 1L, false);

        verify(usuarioRepository, never()).save(any());
        verify(auditoriaEliminacionUsuarioRepository, never()).save(any());
    }
```

- [ ] **Step 2: Correr los tests y confirmar que fallan**

Run: `./mvnw test -Dtest=UsuarioEliminacionServiceTest`
Expected: si algún assert no coincide con el comportamiento real de `eliminarComoAdmin` (ya implementado en Task 7), el test correspondiente falla — de lo contrario, todos pasan directamente porque la lógica ya existe. Confirmar cuál es el caso antes de seguir.

- [ ] **Step 3: Ajustar si hace falta**

Si algún test falló en el Step 2, ajustar `eliminarComoAdmin`/`eliminar` en `UsuarioEliminacionService.java` (Task 7) hasta que el comportamiento coincida con los asserts. Si todos pasaron ya en el Step 2, no hay cambios de producción que hacer en este task.

- [ ] **Step 4: Correr los tests y confirmar que pasan**

Run: `./mvnw test -Dtest=UsuarioEliminacionServiceTest`
Expected: PASS (los 13 tests del archivo completo)

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioEliminacionServiceTest.java
git commit -m "test: cubre la eliminacion de cuenta por ADMIN (403, EMPLOYEE, forzar, idempotencia)"
```

---

## Task 9: `DELETE /api/v1/usuarios/me`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/EliminarCuentaRequest.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerEliminarMeTest.java`

**Interfaces:**
- Consumes: `UsuarioEliminacionService.autoeliminar(String, String)` (Task 7).
- Produces: `DELETE /api/v1/usuarios/me` → `204 No Content` / `401` (password incorrecta o sin token) / `403` (rol EMPLOYEE/ADMIN) / `400` (OWNER con establecimientos activos, vía `EstablecimientosActivosException`).

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerEliminarMeTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-eliminar-me;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/usuarios/me")
class UsuarioControllerEliminarMeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("passwordCorrecta_Anonimiza_Y_Devuelve204")
    void passwordCorrecta_Anonimiza_Y_Devuelve204() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Jugador Test")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(jugador))
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isNoContent());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertEquals("deleted+" + jugador.getId() + "@saque.deleted", recargado.getEmail());
        assertEquals("Usuario eliminado", recargado.getNombre());
        assertNotNull(recargado.getDeletedAt());
        assertEquals(false, recargado.getIsActive());
    }

    @Test
    @DisplayName("passwordIncorrecta_Devuelve401YNoModificaLaCuenta")
    void passwordIncorrecta_Devuelve401YNoModificaLaCuenta() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador2@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Jugador Test 2")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(jugador))
                        .contentType("application/json")
                        .content("{\"password\":\"Incorrecta\"}"))
                .andExpect(status().isUnauthorized());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNull(recargado.getDeletedAt());
    }

    @Test
    @DisplayName("rolEmployee_Devuelve403")
    void rolEmployee_Devuelve403() throws Exception {
        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(empleado))
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=UsuarioControllerEliminarMeTest`
Expected: FAIL — `DELETE /api/v1/usuarios/me` no existe todavía (404/405).

- [ ] **Step 3: Crear el DTO**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/EliminarCuentaRequest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO para DELETE /api/v1/usuarios/me: reconfirma la identidad con la contraseña actual
 * antes de anonimizar la cuenta (ver UsuarioEliminacionService.autoeliminar).
 */
public record EliminarCuentaRequest(
        @NotBlank(message = "La contraseña es obligatoria")
        String password
) {
}
```

- [ ] **Step 4: Agregar el endpoint al controller**

En `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java`:

1. Agregar imports: `com.matiasmeira.sacaladelangulo.auth.dto.EliminarCuentaRequest` y `com.matiasmeira.sacaladelangulo.auth.service.UsuarioEliminacionService`.
2. Agregar el campo (junto a `usuarioService`, línea 22):

```java
    private final UsuarioEliminacionService usuarioEliminacionService;
```

3. Agregar el método (por ejemplo, después de `me`, línea 42-46):

```java
    @DeleteMapping("/me")
    public ResponseEntity<Void> eliminarMiCuenta(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid EliminarCuentaRequest request) {
        String email = userDetails.getUsername();
        usuarioEliminacionService.autoeliminar(email, request.password());
        return ResponseEntity.noContent().build();
    }
```

(`@RequestMapping("/api/v1/usuarios")` de la clase ya cubre el prefijo; `@DeleteMapping`/`ResponseEntity` ya están importados vía `org.springframework.web.bind.annotation.*` / `org.springframework.http.ResponseEntity`.)

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=UsuarioControllerEliminarMeTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/EliminarCuentaRequest.java \
        src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerEliminarMeTest.java
git commit -m "feat: agrega DELETE /api/v1/usuarios/me (autoeliminacion de cuenta)"
```

---

## Task 10: `DELETE /api/v1/admin/usuarios/{id}`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioController.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioControllerTest.java`

**Interfaces:**
- Consumes: `UsuarioEliminacionService.eliminarComoAdmin(String, Long, boolean)` (Task 7).
- Produces: `DELETE /api/v1/admin/usuarios/{id}?forzar=true|false` → `204 No Content` / `403` (no-ADMIN) / `400` (target EMPLOYEE, u OWNER con establecimientos activos sin `forzar`) / `404` (target inexistente).

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioControllerTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-admin-usuarios;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/admin/usuarios/{id}")
class AdminUsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("admin_EliminaCuentaDeJugador_Devuelve204")
    void admin_EliminaCuentaDeJugador_Devuelve204() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador@admin-usuarios-test.com")
                .password("hash")
                .nombre("Jugador Test")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNotNull(recargado.getDeletedAt());
        assertEquals("Usuario eliminado", recargado.getNombre());
    }

    @Test
    @DisplayName("noAdmin_Devuelve403")
    void noAdmin_Devuelve403() throws Exception {
        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Jugador Test 2")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isForbidden());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNull(recargado.getDeletedAt());
    }

    @Test
    @DisplayName("targetEmployee_Devuelve400")
    void targetEmployee_Devuelve400() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 2")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@admin-usuarios-test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + empleado.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetOwnerConEstablecimientoActivoSinForzar_Devuelve400")
    void targetOwnerConEstablecimientoActivoSinForzar_Devuelve400() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin3@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 3")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test 2")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Activo")
                .direccion("Calle Falsa 123")
                .slug("complejo-activo-admin-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(owner)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + owner.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetOwnerConEstablecimientoActivoForzando_Devuelve204YNoDesactivaElComplejo")
    void targetOwnerConEstablecimientoActivoForzando_Devuelve204YNoDesactivaElComplejo() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin4@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 4")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner3@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test 3")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Forzado")
                .direccion("Calle Falsa 456")
                .slug("complejo-forzado-admin-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(owner)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + owner.getId() + "?forzar=true")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario ownerRecargado = usuarioRepository.findById(owner.getId()).orElseThrow();
        assertNotNull(ownerRecargado.getDeletedAt());

        Establecimiento establecimientoRecargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertEquals(true, establecimientoRecargado.getIsActive());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=AdminUsuarioControllerTest`
Expected: FAIL — `AdminUsuarioController` no existe (404).

- [ ] **Step 3: Crear el controller**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioController.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.service.UsuarioEliminacionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de administración para eliminar/anonimizar cualquier cuenta (PLAYER/OWNER/ADMIN).
 * Requiere autenticación (cae bajo el anyRequest().authenticated() de SecurityConfig) y la
 * autorización de rol ADMIN se valida a mano dentro del service, mismo patrón que
 * AdminMailsController/OfertaMarketingService (no se usa @PreAuthorize en este repo).
 */
@RestController
@RequestMapping("/api/v1/admin/usuarios")
@RequiredArgsConstructor
public class AdminUsuarioController {

    private final UsuarioEliminacionService usuarioEliminacionService;

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(
            @PathVariable Long id,
            @RequestParam(name = "forzar", defaultValue = "false") boolean forzar,
            @AuthenticationPrincipal UserDetails userDetails) {
        usuarioEliminacionService.eliminarComoAdmin(userDetails.getUsername(), id, forzar);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=AdminUsuarioControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioController.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/AdminUsuarioControllerTest.java
git commit -m "feat: agrega DELETE /api/v1/admin/usuarios/{id} (eliminacion de cuenta por ADMIN)"
```

---

## Task 11: Mail de confirmación de baja

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListener.java`
- Create: `src/main/resources/templates/email/cuenta-eliminada.html`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListenerTest.java`

**Interfaces:**
- Consumes: `CuentaEliminadaEvent(String email, String nombre)` (Task 7).
- Produces: nada consumido por otras tareas — es el extremo final de la cadena de eventos.

- [ ] **Step 1: Escribir el test que falla**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListenerTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CuentaEliminadaEmailListener - Mail de confirmacion de baja")
class CuentaEliminadaEmailListenerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private EmailRenderer emailRenderer;

    @InjectMocks
    private CuentaEliminadaEmailListener listener;

    @Test
    @DisplayName("enviarEmailDeConfirmacion_RenderizaYEnviaAlEmailReal")
    void enviarEmailDeConfirmacion_RenderizaYEnviaAlEmailReal() {
        when(emailRenderer.render(eq("cuenta-eliminada"), eq(Map.of("nombre", "Juan")))).thenReturn("<html>baja</html>");

        listener.enviarEmailDeConfirmacion(new CuentaEliminadaEvent("juan@test.com", "Juan"));

        verify(emailService).enviar("juan@test.com", "Tu cuenta fue eliminada", "<html>baja</html>");
    }
}
```

- [ ] **Step 2: Correr el test y confirmar que falla**

Run: `./mvnw test -Dtest=CuentaEliminadaEmailListenerTest`
Expected: FAIL — no compila (`CuentaEliminadaEmailListener` no existe).

- [ ] **Step 3: Crear el listener**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListener.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Envía el mail de confirmación de baja de cuenta fuera de la transacción que la dispara
 * (ver UsuarioEliminacionService), mismo motivo AFTER_COMMIT + @Async que
 * RecuperacionPasswordEmailListener. No necesita refetch de entidad: el evento ya lleva
 * el email y el nombre reales, capturados antes de anonimizar.
 */
@Component
@RequiredArgsConstructor
public class CuentaEliminadaEmailListener {

    private static final String ASUNTO = "Tu cuenta fue eliminada";

    private final EmailService emailService;
    private final EmailRenderer emailRenderer;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void enviarEmailDeConfirmacion(CuentaEliminadaEvent evento) {
        String html = emailRenderer.render("cuenta-eliminada", Map.of("nombre", evento.nombre()));
        emailService.enviar(evento.email(), ASUNTO, html);
    }
}
```

- [ ] **Step 4: Crear el template**

Crear `src/main/resources/templates/email/cuenta-eliminada.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8"/>
    <title>Tu cuenta fue eliminada</title>
</head>
<body>

<div th:replace="~{layout :: email(titulo='Tu cuenta fue eliminada', contenidoHtml=~{::#contenido})}">

    <div id="contenido">
        <p style="margin:0 0 16px 0;">
            Hola <span th:text="${nombre}">Usuario</span>, confirmamos que tu cuenta de Saque
            fue eliminada.
        </p>
        <p style="margin:0;">
            Tus datos personales fueron anonimizados. Si no fuiste vos quien solicitó esta
            baja, contactanos lo antes posible.
        </p>
    </div>

</div>

</body>
</html>
```

- [ ] **Step 5: Correr el test y confirmar que pasa**

Run: `./mvnw test -Dtest=CuentaEliminadaEmailListenerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListener.java \
        src/main/resources/templates/email/cuenta-eliminada.html \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/service/CuentaEliminadaEmailListenerTest.java
git commit -m "feat: agrega el mail de confirmacion de baja de cuenta"
```

---

## Task 12: Suite completa

**Files:**
- (ninguno — solo verificación)

**Interfaces:**
- Consumes: todo lo anterior.

- [ ] **Step 1: Correr toda la suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS, sin tests fallidos ni saltados inesperadamente.

- [ ] **Step 2: Si algo falla, diagnosticar y arreglar**

Si algún test de un módulo no tocado por este plan falla (por ejemplo, algo que dependía de un comportamiento de `isActive` o de `ReservaNotificacionListener` que este plan cambió), revisar si es una regresión real de las Tasks 1-11 o un test que asumía el comportamiento viejo a propósito. Corregir el código de producción (no debilitar el test) salvo que el test esté afirmando explícitamente el comportamiento que este plan cambió intencionalmente (ver Global Constraints).

- [ ] **Step 3: Commit final (si Step 2 requirió cambios)**

```bash
git add -A
git commit -m "fix: ajustes finales tras correr la suite completa (baja de cuenta)"
```

(Si el Step 1 ya pasó sin cambios, no hay commit en este task — el plan queda cerrado en el commit del Task 11.)
