# Turno fijo como unidad — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un turno fijo semanal exista como una entidad propia que se puede ver, cancelar, renovar y corregir, en vez de N reservas sueltas sin marca de pertenencia.

**Architecture:** Una entidad `TurnoFijo` guarda la **regla** (cancha, día, horario, período, cliente) y las `Reserva` siguen siendo las **ocurrencias materializadas**, ahora con una FK nullable hacia la regla. Las ocurrencias no se generan al vuelo: eso rompería el constraint de exclusión de Postgres, la grilla de disponibilidad y el lock pesimista. La creación se mueve a `POST /api/v1/turnos-fijos` y sobre ese recurso cuelgan listar, cancelar, renovar y editar cliente.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Flyway (Postgres), JUnit 5 + Mockito, H2 para tests. Front: Next.js 15 App Router, TanStack Query, TypeScript, Vitest.

**Spec:** `docs/superpowers/specs/2026-09-06-turno-fijo-como-unidad-design.md`

## Global Constraints

- **Idioma:** todo el código, comentarios, mensajes de error y mensajes de commit en español. Los commits del repo van sin acentos; el código y los comentarios sí los llevan.
- **TDD sin excepciones:** test que falla primero, se corre y se lo ve fallar por el motivo correcto, después la implementación mínima. Ver `superpowers:test-driven-development`.
- **Migración:** la siguiente libre es **V24**. Los tests corren con `spring.flyway.enabled=false` sobre H2, así que **ninguna migración se ejerce en `./mvnw test`** — se valida al levantar la app contra Postgres.
- **Autorización:** escritura con `autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email)`; lectura con `validarLectura(establecimiento, email, AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA)`. No se agrega ningún valor a `PermisoEmpleado`.
- **Códigos de error:** `GlobalExceptionHandler` mapea `IllegalArgumentException` → 400, `AccessDeniedException` → 403, `EntityNotFoundException` → 404, `DataIntegrityViolationException` → 409. **No existe una excepción de negocio que dé 409**, así que todo error de negocio de este plan es `IllegalArgumentException` → 400, igual que el resto de `ReservaService`.
- **Eventos:** un turno fijo publica **un** evento por operación, nunca uno por ocurrencia. El pool de `AsyncConfig` es core 2 / max 5 / cola 50 / `AbortPolicy`: N eventos pierden avisos ya commiteados.
- **Estados terminales:** `FINALIZADA` y `AUSENTE` no se tocan nunca en lote. `AUSENTE` es terminal por diseño (ver el comentario en `ReservaService.cancelarReserva`).
- **Paginación:** cap de 100, como `ReservaService.capPageSize`.

---

## Estructura de archivos

**Backend (`c:\Users\USER\Desktop\sacaladelangulo`)**

| Archivo | Responsabilidad |
|---|---|
| `reserva/model/TurnoFijo.java` | La regla. Entidad. |
| `reserva/model/EstadoTurnoFijo.java` | `ACTIVO` \| `CANCELADO`. |
| `reserva/repository/TurnoFijoRepository.java` | Consultas de la regla. |
| `reserva/service/TurnoFijoService.java` | Crear, listar, cancelar, renovar, editar cliente. |
| `reserva/controller/TurnoFijoController.java` | `/api/v1/turnos-fijos`. |
| `reserva/dto/TurnoFijoResponse.java` | La regla + agregados. |
| `reserva/dto/TurnoFijoDetalleResponse.java` | La regla + ocurrencias. |
| `reserva/dto/CancelarTurnoFijoRequest.java` | `{ desde? }`. |
| `reserva/dto/CancelacionTurnoFijoResponse.java` | `{ canceladas, omitidas }`. |
| `reserva/dto/EditarClienteTurnoFijoRequest.java` | `{ nombre, telefono }`. |
| `reserva/dto/TurnoFijoMapper.java` | Entidad → DTOs. |
| `reserva/service/TurnoFijoCanceladoEvent.java` | Un evento por cancelación. |
| `db/migration/V24__turnos_fijos.sql` | Tabla, FK, índices, CHECKs. |

`ReservaService.crearReservaSemanal` y `ReservaController` pierden el turno fijo; `ReservaResponse`/`ReservaMapper` suman `turnoFijoId`; `IdempotencyFilter` cambia una ruta; `ReservaNotificacionListener` suma el handler de cancelación.

**Front (`c:\Users\USER\Desktop\saque-front`)**

| Archivo | Responsabilidad |
|---|---|
| `lib/api/tipos/turnos-fijos.ts` | Tipos del recurso. |
| `lib/api/endpoints/turnos-fijos.ts` | Llamadas. |
| `hooks/api/use-turnos-fijos.ts` | Queries y mutaciones. |
| `app/panel/turnos-fijos/page.tsx` | Listado y acciones. |
| `components/panel/dialogo-cancelar-turno-fijo.tsx` | Confirmación con conteo. |
| `lib/panel/turno-fijo.ts` | Suma `inicioDeRenovacion` y `ocurrenciasACancelar`. |

---

## FASE 1 — Modelo, creación linkeada y badge en la agenda

### Task 1: Entidad `TurnoFijo`, repositorio y migración V24

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/model/EstadoTurnoFijo.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/model/TurnoFijo.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/TurnoFijoRepository.java`
- Create: `src/main/resources/db/migration/V24__turnos_fijos.sql`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/TurnoFijoRepositoryTest.java`

**Interfaces:**
- Consumes: `Cancha`, `Usuario`, `Deporte` (ya existen).
- Produces: `TurnoFijo` (getters Lombok, `@Builder`), `EstadoTurnoFijo.ACTIVO|CANCELADO`,
  `TurnoFijoRepository.findByIdConCanchaYEstablecimiento(Long): Optional<TurnoFijo>`,
  `TurnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(Long, EstadoTurnoFijo, Pageable): Page<TurnoFijo>`,
  `TurnoFijoRepository.findByCancha_Establecimiento_Id(Long, Pageable): Page<TurnoFijo>`.

- [ ] **Step 1: Escribir el test que falla**

`TurnoFijoRepositoryTest.java`. Usa `@DataJpaTest` sobre H2, el mismo patrón que ya usa `AuthServiceEmpleadoHomonimoTest`.

```java
package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import jakarta.persistence.EntityManager;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@TestPropertySource(properties = "spring.flyway.enabled=false")
@DisplayName("TurnoFijoRepository")
class TurnoFijoRepositoryTest {

    @Autowired private TurnoFijoRepository turnoFijoRepository;
    @Autowired private EntityManager em;

    /** Deja persistido un establecimiento con una cancha y devuelve la cancha. */
    private Cancha canchaPersistida() {
        Usuario dueno = Usuario.builder()
                .nombre("Dueno").email("dueno@test.com").password("x")
                .rol(Role.OWNER).isActive(true).tokenVersion(0)
                .build();
        em.persist(dueno);

        Establecimiento est = Establecimiento.builder()
                .nombre("Complejo").dueno(dueno).isActive(true)
                .build();
        em.persist(est);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha 1").establecimiento(est).isActive(true)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .build();
        em.persist(cancha);
        em.flush();
        return cancha;
    }

    @Test
    @DisplayName("findByCancha_Establecimiento_IdAndEstado_TraeSoloLosDelEstadoPedido")
    void findByEstablecimientoYEstado_TraeSoloLosDelEstadoPedido() {
        Cancha cancha = canchaPersistida();
        Long estId = cancha.getEstablecimiento().getId();

        turnoFijoRepository.save(turnoFijo(cancha, EstadoTurnoFijo.ACTIVO, null));
        turnoFijoRepository.save(turnoFijo(cancha, EstadoTurnoFijo.CANCELADO, LocalDate.of(2026, 10, 1)));

        var activos = turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                estId, EstadoTurnoFijo.ACTIVO, PageRequest.of(0, 10));

        assertThat(activos.getContent()).hasSize(1);
        assertThat(activos.getContent().get(0).getEstado()).isEqualTo(EstadoTurnoFijo.ACTIVO);
    }

    private TurnoFijo turnoFijo(Cancha cancha, EstadoTurnoFijo estado, LocalDate canceladoDesde) {
        return TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2026, 9, 1))
                .fechaFinPeriodo(LocalDate.of(2026, 12, 31))
                .nombreClienteManual("Grupo del Colo")
                .estado(estado)
                .canceladoDesde(canceladoDesde)
                .build();
    }
}
```

> Si los builders de `Usuario`, `Establecimiento` o `Cancha` piden campos obligatorios que acá faltan, completalos mirando la entidad: el objetivo del helper es sólo tener una cancha persistida válida.

- [ ] **Step 2: Correr el test y verlo fallar**

Run: `./mvnw test -Dtest=TurnoFijoRepositoryTest`
Expected: FAIL de compilación — `TurnoFijo`, `EstadoTurnoFijo` y `TurnoFijoRepository` no existen.

- [ ] **Step 3: Crear el enum**

```java
package com.matiasmeira.sacaladelangulo.reserva.model;

/**
 * Estado de la REGLA de un turno fijo, que es distinto del estado de cada ocurrencia.
 * Una serie ACTIVA puede tener ocurrencias canceladas sueltas (el dueño dio de baja un
 * feriado); una serie CANCELADA dejó de generar compromiso a partir de canceladoDesde.
 */
public enum EstadoTurnoFijo {
    ACTIVO,
    CANCELADO
}
```

- [ ] **Step 4: Crear la entidad**

```java
package com.matiasmeira.sacaladelangulo.reserva.model;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * La REGLA de un turno fijo semanal: qué cancha, qué día, qué horario, durante qué período
 * y para quién. Las ocurrencias son Reservas materializadas que apuntan acá con
 * Reserva.turnoFijo.
 *
 * <p>Existe como entidad y no como una simple columna de agrupación en `reservas` porque la
 * regla tiene que sobrevivir a sus ocurrencias: si el dueño cancela las 17 reservas de la
 * serie, con una columna no quedaría nada que renovar.
 *
 * <p>El período es INMUTABLE. Terminar una serie antes de tiempo no acorta fechaFinPeriodo:
 * se marca estado=CANCELADO con canceladoDesde, para no perder hasta dónde llegaba el
 * compromiso original.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "turnos_fijos")
public class TurnoFijo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancha_id", nullable = false)
    private Cancha cancha;

    @Enumerated(EnumType.STRING)
    @Column(name = "deporte_seleccionado", nullable = false)
    private Deporte deporteSeleccionado;

    @Enumerated(EnumType.STRING)
    @Column(name = "dia_semana", nullable = false)
    private DayOfWeek diaSemana;

    @Column(name = "hora_inicio", nullable = false)
    private LocalTime horaInicio;

    @Column(name = "hora_fin", nullable = false)
    private LocalTime horaFin;

    @Column(name = "fecha_inicio_periodo", nullable = false)
    private LocalDate fechaInicioPeriodo;

    @Column(name = "fecha_fin_periodo", nullable = false)
    private LocalDate fechaFinPeriodo;

    /** Nulo en las series de mostrador, que se identifican con nombreClienteManual. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "jugador_id")
    private Usuario jugador;

    @Column(name = "nombre_cliente_manual")
    private String nombreClienteManual;

    @Column(name = "telefono_cliente_manual")
    private String telefonoClienteManual;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTurnoFijo estado;

    /**
     * Desde qué fecha la serie dejó de generar compromiso. No nulo si y sólo si el estado
     * es CANCELADO; hay un CHECK en V24 que lo garantiza a nivel base.
     */
    @Column(name = "cancelado_desde")
    private LocalDate canceladoDesde;

    /**
     * Id de la serie del año anterior que se renovó para crear esta. Con índice único: una
     * serie se renueva UNA vez, y el segundo intento falla en la base aunque el chequeo del
     * servicio no llegue a correr.
     */
    @Column(name = "renovado_desde_id")
    private Long renovadoDesdeId;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    @Version
    private Long version;

    @PrePersist
    public void prePersist() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (estado == null) {
            estado = EstadoTurnoFijo.ACTIVO;
        }
    }
}
```

- [ ] **Step 5: Crear el repositorio**

```java
package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TurnoFijoRepository extends JpaRepository<TurnoFijo, Long> {

    /**
     * Trae la regla con cancha -> establecimiento -> dueño en una sola consulta: todas las
     * operaciones de escritura arrancan validando contra el establecimiento, y las tres
     * asociaciones son LAZY.
     */
    @Query("SELECT t FROM TurnoFijo t " +
           "LEFT JOIN FETCH t.jugador " +
           "JOIN FETCH t.cancha c " +
           "JOIN FETCH c.establecimiento e " +
           "JOIN FETCH e.dueno " +
           "WHERE t.id = :id")
    Optional<TurnoFijo> findByIdConCanchaYEstablecimiento(@Param("id") Long id);

    /** @EntityGraph por el mismo motivo que los listados de ReservaRepository: evitar N+1. */
    @EntityGraph(attributePaths = {"jugador", "cancha"})
    Page<TurnoFijo> findByCancha_Establecimiento_IdAndEstado(Long estId, EstadoTurnoFijo estado, Pageable pageable);

    @EntityGraph(attributePaths = {"jugador", "cancha"})
    Page<TurnoFijo> findByCancha_Establecimiento_Id(Long estId, Pageable pageable);

    boolean existsByRenovadoDesdeId(Long renovadoDesdeId);
}
```

- [ ] **Step 6: Correr el test y verlo pasar**

Run: `./mvnw test -Dtest=TurnoFijoRepositoryTest`
Expected: PASS.

- [ ] **Step 7: Escribir la migración V24**

`src/main/resources/db/migration/V24__turnos_fijos.sql`:

```sql
-- La REGLA de un turno fijo semanal. Las ocurrencias siguen viviendo en `reservas` y
-- apuntan acá con turno_fijo_id: la regla existe para poder renovar y cancelar la serie
-- como unidad, no para reemplazar a las reservas.
CREATE TABLE turnos_fijos (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cancha_id               BIGINT       NOT NULL,
    deporte_seleccionado    VARCHAR(255) NOT NULL,
    dia_semana              VARCHAR(255) NOT NULL,
    hora_inicio             TIME         NOT NULL,
    hora_fin                TIME         NOT NULL,
    fecha_inicio_periodo    DATE         NOT NULL,
    fecha_fin_periodo       DATE         NOT NULL,
    jugador_id              BIGINT,
    nombre_cliente_manual   VARCHAR(255),
    telefono_cliente_manual VARCHAR(255),
    estado                  VARCHAR(255) NOT NULL,
    cancelado_desde         DATE,
    renovado_desde_id       BIGINT,
    fecha_creacion          TIMESTAMP    NOT NULL,
    version                 BIGINT       NOT NULL,
    CONSTRAINT fk_turnos_fijos_cancha   FOREIGN KEY (cancha_id)         REFERENCES canchas (id),
    CONSTRAINT fk_turnos_fijos_jugador  FOREIGN KEY (jugador_id)        REFERENCES usuarios (id),
    CONSTRAINT fk_turnos_fijos_renovado FOREIGN KEY (renovado_desde_id) REFERENCES turnos_fijos (id),
    -- El horario vive sobre una sola fecha (LocalTime, sin 24:00), igual que cada ocurrencia.
    CONSTRAINT chk_turnos_fijos_horas CHECK (hora_inicio < hora_fin),
    CONSTRAINT chk_turnos_fijos_periodo CHECK (fecha_inicio_periodo <= fecha_fin_periodo),
    -- Impide el estado a medias: una serie cancelada siempre dice desde cuándo.
    CONSTRAINT chk_turnos_fijos_cancelacion CHECK (
            (estado = 'ACTIVO'    AND cancelado_desde IS NULL)
         OR (estado = 'CANCELADO' AND cancelado_desde IS NOT NULL))
);

-- Una serie se renueva UNA sola vez: sin esto, dos clicks seguidos en "Renovar" crean dos
-- series idénticas y la segunda recién falla por solapamiento, con un mensaje que no le
-- dice nada al dueño.
CREATE UNIQUE INDEX uk_turnos_fijos_renovado_desde
    ON turnos_fijos (renovado_desde_id)
    WHERE renovado_desde_id IS NOT NULL;

CREATE INDEX idx_turnos_fijos_cancha ON turnos_fijos (cancha_id);

ALTER TABLE reservas ADD COLUMN turno_fijo_id BIGINT;
ALTER TABLE reservas ADD CONSTRAINT fk_reservas_turno_fijo
    FOREIGN KEY (turno_fijo_id) REFERENCES turnos_fijos (id);

-- Parcial: la enorme mayoría de las reservas no son de turno fijo y no tiene sentido
-- indexarlas.
CREATE INDEX idx_reservas_turno_fijo ON reservas (turno_fijo_id)
    WHERE turno_fijo_id IS NOT NULL;
```

> **Ojo:** no se agrega el CHECK del XOR entre `jugador_id` y `nombre_cliente_manual`. La spec lo deja pendiente a propósito: un CHECK mal puesto rompe inserts en producción y ningún test lo ejercería, porque Flyway está apagado en tests.

- [ ] **Step 8: Agregar la FK en `Reserva`**

En `reserva/model/Reserva.java`, después del campo `cancha`:

```java
    /**
     * Serie a la que pertenece esta ocurrencia, si es parte de un turno fijo semanal. Nulo
     * en toda reserva puntual (de jugador o de mostrador), que es la enorme mayoría.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turno_fijo_id")
    private TurnoFijo turnoFijo;
```

- [ ] **Step 9: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS, 748 tests o más. La columna nueva es nullable, así que nada existente se rompe.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/reserva/model/TurnoFijo.java \
        src/main/java/com/matiasmeira/sacaladelangulo/reserva/model/EstadoTurnoFijo.java \
        src/main/java/com/matiasmeira/sacaladelangulo/reserva/model/Reserva.java \
        src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/TurnoFijoRepository.java \
        src/main/resources/db/migration/V24__turnos_fijos.sql \
        src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/TurnoFijoRepositoryTest.java
git commit -m "feat(turno-fijo): agrega la entidad TurnoFijo y la FK desde reservas"
```

---

### Task 2: `TurnoFijoService.crear` — la creación persiste la regla

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/TurnoFijoService.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/TurnoFijoResponse.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/TurnoFijoMapper.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaService.java` (se va `crearReservaSemanal` y sus privados exclusivos)
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/service/TurnoFijoServiceTest.java`

**Interfaces:**
- Consumes: `TurnoFijoRepository` (Task 1), `ReservaSemanalRequest` (existe).
- Produces: `TurnoFijoService.crear(ReservaSemanalRequest, String email): TurnoFijoResponse`,
  `TurnoFijoResponse(Long id, Long canchaId, String canchaNombre, Deporte deporteSeleccionado, DayOfWeek diaSemana, LocalTime horaInicio, LocalTime horaFin, LocalDate fechaInicioPeriodo, LocalDate fechaFinPeriodo, String estado, LocalDate canceladoDesde, Long jugadorId, String jugadorNombre, String nombreClienteManual, String telefonoClienteManual, Long renovadoDesdeId, List<ReservaResponse> ocurrencias)`.

- [ ] **Step 1: Escribir el test que falla**

Mové `ReservaServiceTest` los casos de turno fijo a `TurnoFijoServiceTest` (mismo setup de mocks) y agregá el que fija lo nuevo:

```java
    @Test
    @DisplayName("crear_TurnoFijo_PersisteLaReglaYLinkeaTodasLasOcurrencias")
    void crear_TurnoFijo_PersisteLaReglaYLinkeaTodasLasOcurrencias() {
        // ... mismo arrange que el test de crearReservaSemanal que ya existe:
        //     cancha, establecimiento con dueño, horario de atención, sin bloqueos.
        ReservaSemanalRequest request = new ReservaSemanalRequest(
                CANCHA_ID,
                LocalDate.of(2026, 9, 1),   // martes
                LocalDate.of(2026, 9, 22),
                DayOfWeek.TUESDAY,
                LocalTime.of(20, 0),
                LocalTime.of(21, 0),
                Deporte.FUTBOL_5,
                null,
                "Grupo del Colo",
                "11 5555-4444");

        TurnoFijoResponse respuesta = turnoFijoService.crear(request, EMAIL_DUENO);

        ArgumentCaptor<TurnoFijo> reglaCaptor = ArgumentCaptor.forClass(TurnoFijo.class);
        verify(turnoFijoRepository).save(reglaCaptor.capture());
        TurnoFijo regla = reglaCaptor.getValue();
        assertThat(regla.getDiaSemana()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(regla.getEstado()).isEqualTo(EstadoTurnoFijo.ACTIVO);
        assertThat(regla.getCanceladoDesde()).isNull();

        ArgumentCaptor<List<Reserva>> reservasCaptor = ArgumentCaptor.forClass(List.class);
        verify(reservaRepository).saveAll(reservasCaptor.capture());
        List<Reserva> ocurrencias = reservasCaptor.getValue();
        assertThat(ocurrencias).hasSize(4);
        assertThat(ocurrencias).allSatisfy(r ->
                assertThat(r.getTurnoFijo()).isSameAs(regla));

        assertThat(respuesta.ocurrencias()).hasSize(4);
    }
```

- [ ] **Step 2: Correr el test y verlo fallar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: FAIL de compilación — `TurnoFijoService` no existe.

- [ ] **Step 3: Crear el DTO de respuesta y el mapper**

```java
package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * La regla de un turno fijo. `ocurrencias` viene poblada en el alta y en el detalle, y
 * vacía en el listado: ahí traerlas sería N+1 y el listado no las muestra.
 */
public record TurnoFijoResponse(
        Long id,
        Long canchaId,
        String canchaNombre,
        Deporte deporteSeleccionado,
        DayOfWeek diaSemana,
        LocalTime horaInicio,
        LocalTime horaFin,
        LocalDate fechaInicioPeriodo,
        LocalDate fechaFinPeriodo,
        String estado,
        LocalDate canceladoDesde,
        Long jugadorId,
        String jugadorNombre,
        String nombreClienteManual,
        String telefonoClienteManual,
        Long renovadoDesdeId,
        List<ReservaResponse> ocurrencias
) {
}
```

```java
package com.matiasmeira.sacaladelangulo.reserva.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TurnoFijoMapper {

    private final ReservaMapper reservaMapper;

    public TurnoFijoResponse mapToResponse(TurnoFijo turnoFijo, List<ReservaResponse> ocurrencias) {
        Usuario jugador = turnoFijo.getJugador();
        return new TurnoFijoResponse(
                turnoFijo.getId(),
                turnoFijo.getCancha().getId(),
                turnoFijo.getCancha().getNombre(),
                turnoFijo.getDeporteSeleccionado(),
                turnoFijo.getDiaSemana(),
                turnoFijo.getHoraInicio(),
                turnoFijo.getHoraFin(),
                turnoFijo.getFechaInicioPeriodo(),
                turnoFijo.getFechaFinPeriodo(),
                turnoFijo.getEstado().name(),
                turnoFijo.getCanceladoDesde(),
                jugador != null ? jugador.getId() : null,
                jugador != null ? jugador.getNombre() : null,
                turnoFijo.getNombreClienteManual(),
                turnoFijo.getTelefonoClienteManual(),
                turnoFijo.getRenovadoDesdeId(),
                ocurrencias
        );
    }

    /** Para el listado: la regla sin sus ocurrencias. */
    public TurnoFijoResponse mapToResponse(TurnoFijo turnoFijo) {
        return mapToResponse(turnoFijo, List.of());
    }
}
```

- [ ] **Step 4: Crear `TurnoFijoService` moviendo `crearReservaSemanal`**

Mové **tal cual** el cuerpo de `ReservaService.crearReservaSemanal` (líneas 269-370) y los privados que sólo él usa (`validarPeriodoDentroDelAnio`, `generarFechasDelPeriodo`). Los privados compartidos (`validarFechas`, `validarGranularidadHoraria`, `validarDuracion`, `validarSinBloqueos`, `validarDiaNoLaborable`, `validarHorarioAtencion`, `validarCanchaExactaLibre`, `validarPoolCanchas`, `bloquearCanchasRelacionadas`, `calcularPrecio`, `seSuperponen`, `buscarCanchaPorId`) **pasan de `private` a package-private** en `ReservaService` y `TurnoFijoService` los llama por inyección.

> No los dupliques. Duplicar `validarHorarioAtencion` es cómo el turno fijo y la reserva puntual terminan discrepando sobre si un complejo abre.

Los tres cambios de comportamiento respecto del método original:

```java
        // 1) La regla se persiste ANTES que las ocurrencias, para tener el id que va en la FK.
        TurnoFijo regla = turnoFijoRepository.save(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(request.deporteSeleccionado())
                .diaSemana(request.diaSemana())
                .horaInicio(request.horaInicio())
                .horaFin(request.horaFin())
                .fechaInicioPeriodo(request.fechaInicioPeriodo())
                .fechaFinPeriodo(request.fechaFinPeriodo())
                .jugador(jugador)
                .nombreClienteManual(jugador == null ? request.nombreClienteManual() : null)
                .telefonoClienteManual(jugador == null ? request.telefonoClienteManual() : null)
                .estado(EstadoTurnoFijo.ACTIVO)
                .build());

        // 2) Cada ocurrencia apunta a la regla: se agrega .turnoFijo(regla) al builder de Reserva
        //    que ya existe dentro del for.

        // 3) La respuesta es la regla con sus ocurrencias adentro, no una lista pelada.
        return turnoFijoMapper.mapToResponse(regla,
                reservasGuardadas.stream().map(reservaMapper::mapToResponse).toList());
```

- [ ] **Step 5: Correr los tests y verlos pasar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: PASS.

- [ ] **Step 6: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS. `ReservaServiceTest` ya no tiene los casos de turno fijo (se movieron).

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/com/matiasmeira/sacaladelangulo/reserva src/test/java/com/matiasmeira/sacaladelangulo/reserva
git commit -m "feat(turno-fijo): mueve la creacion a TurnoFijoService y linkea las ocurrencias a la regla"
```

---

### Task 3: Controller, ruta nueva e idempotencia

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/controller/TurnoFijoController.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/controller/ReservaController.java` (se va `POST /semanal`)
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/core/idempotencia/IdempotencyFilter.java:57-62`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/core/RutasProtegidasCoincidenConControllersTest.java` (ya existe, no se toca — tiene que seguir verde)

**Interfaces:**
- Consumes: `TurnoFijoService.crear` (Task 2).
- Produces: `POST /api/v1/turnos-fijos` → 201 `TurnoFijoResponse`.

- [ ] **Step 1: Cambiar la ruta protegida y verla fallar**

En `IdempotencyFilter.RUTAS_PROTEGIDAS`, reemplazá `"/api/v1/reservas/semanal"` por `"/api/v1/turnos-fijos"`.

Run: `./mvnw test -Dtest=RutasProtegidasCoincidenConControllersTest`
Expected: FAIL — `/api/v1/turnos-fijos` todavía no resuelve a ningún `@PostMapping`.

> Este es el orden correcto: primero el test que prueba que la ruta protegida existe de verdad. Si hicieras el controller primero, nunca verías fallar el guard que impide que el filtro y los controllers se desincronicen.

- [ ] **Step 2: Crear el controller**

```java
package com.matiasmeira.sacaladelangulo.reserva.controller;

import com.matiasmeira.sacaladelangulo.reserva.dto.ReservaSemanalRequest;
import com.matiasmeira.sacaladelangulo.reserva.dto.TurnoFijoResponse;
import com.matiasmeira.sacaladelangulo.reserva.service.TurnoFijoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Turnos fijos semanales. La creación vivía en ReservaController como POST
 * /reservas/semanal; se movió acá para que la serie sea un recurso propio sobre el que
 * cuelgan listar, cancelar, renovar y editar cliente.
 */
@RestController
@RequestMapping("/api/v1/turnos-fijos")
@RequiredArgsConstructor
public class TurnoFijoController {

    private final TurnoFijoService turnoFijoService;

    /**
     * Crea un turno fijo: la regla más una reserva CONFIRMADA por cada fecha del período
     * que cae en el día pedido. Todo-o-nada. Sólo el dueño real del establecimiento o un
     * admin: un empleado no puede comprometer la agenda a un año.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TurnoFijoResponse> crear(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ReservaSemanalRequest request) {
        TurnoFijoResponse turnoFijo = turnoFijoService.crear(request, userDetails.getUsername());
        return ResponseEntity.status(HttpStatus.CREATED).body(turnoFijo);
    }
}
```

Y borrá de `ReservaController` el método `crearReservaSemanal` con su `@PostMapping("/semanal")` y los imports que queden sin uso.

- [ ] **Step 3: Correr el test y verlo pasar**

Run: `./mvnw test -Dtest=RutasProtegidasCoincidenConControllersTest`
Expected: PASS.

- [ ] **Step 4: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(turno-fijo): expone POST /api/v1/turnos-fijos y mueve la ruta protegida"
```

---

### Task 4: `turnoFijoId` en `ReservaResponse`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/ReservaResponse.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/ReservaMapper.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/dto/ReservaMapperTest.java`

**Interfaces:**
- Produces: `ReservaResponse.turnoFijoId(): Long` (nullable), último campo del record.

- [ ] **Step 1: Escribir el test que falla**

```java
    @Test
    @DisplayName("mapToResponse_ReservaDeTurnoFijo_ExponeElIdDeLaSerie")
    void mapToResponse_ReservaDeTurnoFijo_ExponeElIdDeLaSerie() {
        TurnoFijo regla = TurnoFijo.builder().id(7L).build();
        Reserva reserva = reservaBase();   // helper que ya exista, o armala con el builder
        reserva.setTurnoFijo(regla);

        assertThat(reservaMapper.mapToResponse(reserva).turnoFijoId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("mapToResponse_ReservaPuntual_DejaElIdDeSerieEnNulo")
    void mapToResponse_ReservaPuntual_DejaElIdDeSerieEnNulo() {
        assertThat(reservaMapper.mapToResponse(reservaBase()).turnoFijoId()).isNull();
    }
```

> Si `ReservaMapperTest` no existe, crealo con `ReservaMapper` instanciado a mano (`new ReservaMapper()`): no tiene dependencias.

- [ ] **Step 2: Correr y verlo fallar**

Run: `./mvnw test -Dtest=ReservaMapperTest`
Expected: FAIL de compilación — `turnoFijoId()` no existe.

- [ ] **Step 3: Agregar el campo**

En `ReservaResponse`, como último componente del record:

```java
        String metodoPago,
        /** Id de la serie si esta reserva es una ocurrencia de un turno fijo; null si no. */
        Long turnoFijoId
```

Y en `ReservaMapper.mapToResponse`, como último argumento:

```java
                reserva.getMetodoPago() != null ? reserva.getMetodoPago().name() : null,
                reserva.getTurnoFijo() != null ? reserva.getTurnoFijo().getId() : null
```

- [ ] **Step 4: Correr y verlo pasar**

Run: `./mvnw test -Dtest=ReservaMapperTest`
Expected: PASS.

- [ ] **Step 5: Correr la suite completa**

Run: `./mvnw test`
Expected: PASS. Si algún test construye `ReservaResponse` a mano, agregale el `null` final.

> `reserva.getTurnoFijo()` es LAZY. En los listados paginados esto dispara una consulta por fila si la reserva pertenece a una serie. Agregá `"turnoFijo"` a los `@EntityGraph` de `ReservaRepository` que ya listan `{"jugador", "cancha"}`: son todos `@ManyToOne`, así que el LEFT JOIN extra no rompe la paginación.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(turno-fijo): expone turnoFijoId en ReservaResponse"
```

---

### Task 5: Front — badge en la agenda y contrato nuevo

**Files:**
- Modify: `src/lib/api/tipos/reservas.ts`
- Modify: `src/lib/api/endpoints/reservas.ts` (se va `crearSemanal`)
- Create: `src/lib/api/tipos/turnos-fijos.ts`
- Create: `src/lib/api/endpoints/turnos-fijos.ts`
- Modify: `src/hooks/api/use-agenda.ts`
- Modify: `src/lib/api/adaptadores/agenda.ts`
- Modify: `src/app/panel/agenda/page.tsx`
- Modify: `src/components/panel/timeline-agenda.tsx`

**Interfaces:**
- Consumes: `POST /api/v1/turnos-fijos` (Task 3), `ReservaResponse.turnoFijoId` (Task 4).
- Produces: `TurnoConReserva.turnoFijoId: number | null`, `turnosFijos.crear(body): Promise<TurnoFijoResponse>`.

- [ ] **Step 1: Escribir el test que falla**

En `src/lib/api/adaptadores/agenda.test.ts` (crealo si no existe):

```ts
import { describe, expect, it } from "vitest";
import { aTurno } from "./agenda";

const reservaBase = {
  id: 1, jugadorId: null, jugadorNombre: null, canchaId: 3, canchaNombre: "Cancha 1",
  fechaHoraInicio: "2026-09-08T20:00:00", fechaHoraFin: "2026-09-08T21:00:00",
  estado: "CONFIRMADA" as const, precioTotal: 15000, senaPagada: 0,
  nombreClienteManual: "Grupo del Colo", telefonoClienteManual: "11 5555-4444",
  deporteSeleccionado: "FUTBOL_5" as const, expiraEn: null, metodoPago: null,
};

describe("aTurno", () => {
  it("propaga el id de la serie cuando la reserva es de un turno fijo", () => {
    expect(aTurno({ ...reservaBase, turnoFijoId: 7 }).turnoFijoId).toBe(7);
  });

  it("deja el id de serie en null en una reserva puntual", () => {
    expect(aTurno({ ...reservaBase, turnoFijoId: null }).turnoFijoId).toBeNull();
  });
});
```

- [ ] **Step 2: Correr y verlo fallar**

Run: `npm test -- agenda`
Expected: FAIL — `turnoFijoId` no existe en el tipo ni en el resultado.

- [ ] **Step 3: Agregar el campo en tipos y adaptador**

En `tipos/reservas.ts`, dentro de `ReservaResponse`:

```ts
  /** Id de la serie si la reserva es una ocurrencia de un turno fijo. */
  turnoFijoId: number | null;
```

En `adaptadores/agenda.ts`, en `TurnoConReserva` y en el objeto que devuelve `aTurno`:

```ts
  turnoFijoId: reserva.turnoFijoId,
```

Y **borrá** el párrafo del comentario de cabecera que dice que `repiteSemanal` no existe porque no hay marca de pertenencia a la serie: ya no es cierto. Reemplazalo por:

```
 *  - `turnoFijoId` viene con valor cuando la reserva es una ocurrencia de un
 *    turno fijo semanal, y null cuando es puntual. La serie completa se
 *    gestiona en /panel/turnos-fijos.
```

- [ ] **Step 4: Correr y verlo pasar**

Run: `npm test -- agenda`
Expected: PASS.

- [ ] **Step 5: Mover el endpoint de creación**

Creá `tipos/turnos-fijos.ts` con `TurnoFijoResponse` (espeja el record de Task 2) y `endpoints/turnos-fijos.ts`:

```ts
import { apiFetch, nuevaIdempotencyKey } from "../cliente";
import type { ReservaSemanalRequest } from "../tipos/reservas";
import type { TurnoFijoResponse } from "../tipos/turnos-fijos";

/** TurnoFijoController — base /api/v1/turnos-fijos. */
export const turnosFijos = {
  /**
   * Crea la serie: la regla más una reserva CONFIRMADA por cada fecha del
   * período que cae en `diaSemana`. Devuelve la regla con sus ocurrencias.
   *
   * TODO-O-NADA: si una sola fecha choca no se crea ninguna y el 400 dice cuál.
   * Idempotency-Key es OBLIGATORIA: sin ella el back responde 400 sin crear nada.
   */
  crear: (body: ReservaSemanalRequest) =>
    apiFetch<TurnoFijoResponse>("/api/v1/turnos-fijos", {
      method: "POST",
      body,
      idempotencyKey: nuevaIdempotencyKey(),
    }),
};
```

Borrá `reservas.crearSemanal`, y en `use-agenda.ts` apuntá la mutación `crearSemanal` a `turnosFijos.crear`.

- [ ] **Step 6: Badge en el timeline**

En `timeline-agenda.tsx`, dentro del bloque que dibuja cada turno, cuando `turno.turnoFijoId` no es null, agregá el ícono `Repeat` de lucide-react con `aria-label="Turno fijo"`. Seguí el patrón de los badges de estado que ya están en ese archivo.

- [ ] **Step 7: Verificar todo**

```bash
npm test && npm run typecheck && npm run build
```
Expected: verde. El lint tiene 2 errores y 2 warnings preexistentes en `wizard-onboarding.tsx` y `use-wizard-onboarding.ts`: ese es el baseline, no lo empeores.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(panel): marca en la agenda los turnos que son parte de una serie"
```

---

## FASE 2 — Listado, detalle y cancelación

### Task 6: Listado y detalle

**Files:**
- Modify: `TurnoFijoService.java`, `TurnoFijoController.java`, `TurnoFijoRepository.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/TurnoFijoListadoResponse.java`
- Test: `TurnoFijoServiceTest.java`

**Interfaces:**
- Produces: `TurnoFijoService.listar(Long estId, EstadoTurnoFijo estado, Pageable, String email): Page<TurnoFijoListadoResponse>`, `TurnoFijoService.detalle(Long id, String email): TurnoFijoResponse`.
  `TurnoFijoListadoResponse` = `TurnoFijoResponse` sin `ocurrencias` y con `long ocurrenciasActivas` y `LocalDateTime proximaOcurrencia`.

- [ ] **Step 1: Escribir los tests que fallan**

```java
    @Test
    @DisplayName("listar_SinEstado_TraeSoloLosActivos")
    void listar_SinEstado_TraeSoloLosActivos() {
        // arrange: establecimiento del dueño, un turno fijo ACTIVO y uno CANCELADO
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                eq(EST_ID), eq(EstadoTurnoFijo.ACTIVO), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(turnoFijoActivo)));

        var pagina = turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        assertThat(pagina.getContent()).hasSize(1);
        verify(turnoFijoRepository, never()).findByCancha_Establecimiento_Id(anyLong(), any());
    }

    @Test
    @DisplayName("listar_ResuelveLosAgregadosEnUnaSolaConsulta")
    void listar_ResuelveLosAgregadosEnUnaSolaConsulta() {
        when(turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(anyLong(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(turnoFijoActivo, otroTurnoFijoActivo)));

        turnoFijoService.listar(EST_ID, null, PageRequest.of(0, 20), EMAIL_DUENO);

        // Una sola llamada agregada para toda la página, no una por fila.
        verify(reservaRepository, times(1)).agregadosPorTurnoFijo(anyList(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("detalle_DeOtroEstablecimiento_LanzaAccessDenied")
    void detalle_DeOtroEstablecimiento_LanzaAccessDenied() {
        when(turnoFijoRepository.findByIdConCanchaYEstablecimiento(TURNO_FIJO_ID))
                .thenReturn(Optional.of(turnoFijoActivo));
        doThrow(new AccessDeniedException("No autorizado"))
                .when(autorizacionEmpleadoService).validarLectura(any(), eq(EMAIL_INTRUSO), any());

        assertThatThrownBy(() -> turnoFijoService.detalle(TURNO_FIJO_ID, EMAIL_INTRUSO))
                .isInstanceOf(AccessDeniedException.class);
    }
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: FAIL de compilación — `listar`, `detalle` y `agregadosPorTurnoFijo` no existen.

- [ ] **Step 3: Agregar la consulta agregada**

En `ReservaRepository`:

```java
    /**
     * Cantidad de ocurrencias futuras vivas y fecha de la próxima, por serie, para toda una
     * página del listado de turnos fijos EN UNA SOLA consulta. Sin esto el listado hace dos
     * consultas por fila, que es el N+1 clásico de un listado con agregados.
     */
    @Query("SELECT r.turnoFijo.id, COUNT(r), MIN(r.fechaHoraInicio) FROM Reserva r " +
           "WHERE r.turnoFijo.id IN :turnoFijoIds " +
           "AND r.estado IN ('CONFIRMADA', 'PENDIENTE_SENA') " +
           "AND r.fechaHoraInicio > :ahora " +
           "GROUP BY r.turnoFijo.id")
    List<Object[]> agregadosPorTurnoFijo(@Param("turnoFijoIds") List<Long> turnoFijoIds,
                                         @Param("ahora") LocalDateTime ahora);
```

- [ ] **Step 4: Implementar `listar` y `detalle`**

```java
    /**
     * Listado de turnos fijos del establecimiento. Por defecto sólo los ACTIVOS: los
     * cancelados se piden explícitamente, para auditar.
     *
     * Lectura, no escritura: un empleado que ya ve la agenda ve también las series. No puede
     * cancelarlas ni renovarlas — eso pasa por validarPropietarioOAdmin.
     */
    @Transactional(readOnly = true)
    public Page<TurnoFijoListadoResponse> listar(Long estId, EstadoTurnoFijo estado, Pageable pageable, String email) {
        Establecimiento establecimiento = establecimientoRepository.findById(estId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarLectura(establecimiento, email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        EstadoTurnoFijo estadoBuscado = estado != null ? estado : EstadoTurnoFijo.ACTIVO;
        Page<TurnoFijo> pagina = turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                estId, estadoBuscado, capPageSize(pageable));

        List<Long> ids = pagina.getContent().stream().map(TurnoFijo::getId).toList();
        Map<Long, Object[]> agregados = ids.isEmpty() ? Map.of()
                : reservaRepository.agregadosPorTurnoFijo(ids, LocalDateTime.now()).stream()
                        .collect(Collectors.toMap(fila -> (Long) fila[0], fila -> fila));

        return pagina.map(turnoFijo -> turnoFijoMapper.mapToListado(turnoFijo, agregados.get(turnoFijo.getId())));
    }

    @Transactional(readOnly = true)
    public TurnoFijoResponse detalle(Long id, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarLectura(turnoFijo.getCancha().getEstablecimiento(), email,
                AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        List<ReservaResponse> ocurrencias = reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id)
                .stream().map(reservaMapper::mapToResponse).toList();
        return turnoFijoMapper.mapToResponse(turnoFijo, ocurrencias);
    }

    private TurnoFijo buscarTurnoFijo(Long id) {
        return turnoFijoRepository.findByIdConCanchaYEstablecimiento(id)
                .orElseThrow(() -> new EntityNotFoundException("Turno fijo no encontrado"));
    }
```

Agregá `List<Reserva> findByTurnoFijoIdOrderByFechaHoraInicioAsc(Long turnoFijoId);` a `ReservaRepository`, y `mapToListado` a `TurnoFijoMapper` (mismo mapeo más los dos agregados; si la fila es null, `0` y `null`).

> `capPageSize` es privado de `ReservaService` y `TurnoFijoService` no lo hereda. Movelo a un helper compartido (o hacelo package-private y llamalo por inyección, igual que los validadores de la Task 2) en vez de copiar el `Math.min`: el cap de 100 tiene que ser uno solo, si no el listado nuevo queda sin techo el día que alguien cambie el viejo.

> `TurnoFijoService` necesita `@Slf4j` y los campos `eventPublisher`, `establecimientoRepository`, `reservaRepository`, `reservaMapper` y `turnoFijoMapper` inyectados con `@RequiredArgsConstructor`, igual que `ReservaService`.

- [ ] **Step 5: Agregar los endpoints al controller**

```java
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<Page<TurnoFijoListadoResponse>> listar(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam Long establecimientoId,
            @RequestParam(required = false) EstadoTurnoFijo estado,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(turnoFijoService.listar(
                establecimientoId, estado, pageable, userDetails.getUsername()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'EMPLOYEE')")
    public ResponseEntity<TurnoFijoResponse> detalle(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.ok(turnoFijoService.detalle(id, userDetails.getUsername()));
    }
```

- [ ] **Step 6: Correr y verlos pasar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest` y después `./mvnw test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(turno-fijo): listado y detalle de las series del establecimiento"
```

---

### Task 7: Cancelar la serie

**Files:**
- Modify: `TurnoFijoService.java`, `TurnoFijoController.java`, `ReservaNotificacionListener.java`
- Create: `reserva/service/TurnoFijoCanceladoEvent.java`, `reserva/dto/CancelarTurnoFijoRequest.java`, `reserva/dto/CancelacionTurnoFijoResponse.java`
- Create: `src/main/resources/templates/email/turno-fijo-cancelado.html`
- Test: `TurnoFijoServiceTest.java`, `ReservaNotificacionListenerTest.java`, `EmailRendererTest.java`

**Interfaces:**
- Produces: `TurnoFijoService.cancelar(Long id, LocalDate desde, String email): CancelacionTurnoFijoResponse`,
  `CancelacionTurnoFijoResponse(int canceladas, List<OcurrenciaOmitida> omitidas)` con `OcurrenciaOmitida(LocalDateTime fecha, String motivo)`,
  `TurnoFijoCanceladoEvent(Long turnoFijoId, List<Long> reservaIds)`.

- [ ] **Step 1: Escribir los tests que fallan**

```java
    @Test
    @DisplayName("cancelar_CancelaLasFuturasYDejaIntactasLasPasadas")
    void cancelar_CancelaLasFuturasYDejaIntactasLasPasadas() {
        Reserva pasada = ocurrencia(LocalDateTime.now().minusDays(7), EstadoReserva.CONFIRMADA);
        Reserva futura = ocurrencia(LocalDateTime.now().plusDays(7), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(pasada, futura));

        var resumen = turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        assertThat(resumen.canceladas()).isEqualTo(1);
        assertThat(pasada.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(futura.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("cancelar_OmiteFinalizadasYAusentesYLasReporta")
    void cancelar_OmiteFinalizadasYAusentesYLasReporta() {
        Reserva finalizada = ocurrencia(LocalDateTime.now().plusDays(1), EstadoReserva.FINALIZADA);
        Reserva ausente = ocurrencia(LocalDateTime.now().plusDays(2), EstadoReserva.AUSENTE);
        Reserva viva = ocurrencia(LocalDateTime.now().plusDays(3), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(finalizada, ausente, viva));

        var resumen = turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        assertThat(resumen.canceladas()).isEqualTo(1);
        assertThat(resumen.omitidas()).hasSize(2);
        assertThat(finalizada.getEstado()).isEqualTo(EstadoReserva.FINALIZADA);
        assertThat(ausente.getEstado()).isEqualTo(EstadoReserva.AUSENTE);
    }

    @Test
    @DisplayName("cancelar_PublicaUnSoloEventoYNoUnoPorOcurrencia")
    void cancelar_PublicaUnSoloEventoYNoUnoPorOcurrencia() {
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(
                        ocurrencia(LocalDateTime.now().plusDays(1), EstadoReserva.CONFIRMADA),
                        ocurrencia(LocalDateTime.now().plusDays(8), EstadoReserva.CONFIRMADA),
                        ocurrencia(LocalDateTime.now().plusDays(15), EstadoReserva.CONFIRMADA)));

        turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_DUENO);

        verify(eventPublisher, times(1)).publishEvent(any(TurnoFijoCanceladoEvent.class));
        verify(eventPublisher, never()).publishEvent(any(ReservaCanceladaEvent.class));
    }

    @Test
    @DisplayName("cancelar_DesdeUnaFechaFutura_NoTocaLasAnteriores")
    void cancelar_DesdeUnaFechaFutura_NoTocaLasAnteriores() {
        Reserva antes = ocurrencia(LocalDateTime.now().plusDays(3), EstadoReserva.CONFIRMADA);
        Reserva despues = ocurrencia(LocalDateTime.now().plusDays(17), EstadoReserva.CONFIRMADA);
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(antes, despues));

        turnoFijoService.cancelar(TURNO_FIJO_ID, LocalDate.now().plusDays(10), EMAIL_DUENO);

        assertThat(antes.getEstado()).isEqualTo(EstadoReserva.CONFIRMADA);
        assertThat(despues.getEstado()).isEqualTo(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("cancelar_ComoEmpleado_LanzaAccessDenied")
    void cancelar_ComoEmpleado_LanzaAccessDenied() {
        doThrow(new AccessDeniedException("No autorizado"))
                .when(autorizacionEmpleadoService).validarPropietarioOAdmin(any(), eq(EMAIL_EMPLEADO));

        assertThatThrownBy(() -> turnoFijoService.cancelar(TURNO_FIJO_ID, null, EMAIL_EMPLEADO))
                .isInstanceOf(AccessDeniedException.class);
    }
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: FAIL de compilación — `cancelar` no existe.

- [ ] **Step 3: Crear el evento y los DTOs**

```java
package com.matiasmeira.sacaladelangulo.reserva.service;

import java.util.List;

/**
 * Evento publicado UNA sola vez por cada turno fijo cancelado, con los ids de las
 * ocurrencias efectivamente dadas de baja.
 *
 * <p>Mismo motivo que {@link TurnoFijoCreadoEvent}: publicar un ReservaCanceladaEvent por
 * ocurrencia encolaba una tarea @Async por fecha contra el pool de AsyncConfig (core 2,
 * max 5, cola 50, AbortPolicy). Una serie anual cancelada de entrada son ~52 tareas, más de
 * lo que la cola aguanta junto con el resto de los @Async del sistema. Y para el destinatario
 * es UN aviso ("se dio de baja tu turno de los martes") con la lista de fechas, no 52 mails.
 *
 * @param turnoFijoId Id de la serie cancelada
 * @param reservaIds  Ids de las ocurrencias canceladas, en orden cronológico
 */
public record TurnoFijoCanceladoEvent(Long turnoFijoId, List<Long> reservaIds) {
}
```

```java
package com.matiasmeira.sacaladelangulo.reserva.dto;

import java.time.LocalDate;

/** `desde` opcional: si no viene, la serie se corta desde ahora. */
public record CancelarTurnoFijoRequest(LocalDate desde) {
}
```

```java
package com.matiasmeira.sacaladelangulo.reserva.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resultado de cancelar una serie. `omitidas` no es decorativo: una ocurrencia FINALIZADA o
 * AUSENTE no se cancela nunca, y si el dueño no lo ve, cree que la serie está muerta
 * mientras sigue apareciendo en los reportes.
 */
public record CancelacionTurnoFijoResponse(
        int canceladas,
        List<OcurrenciaOmitida> omitidas
) {
    public record OcurrenciaOmitida(LocalDateTime fecha, String motivo) {
    }
}
```

- [ ] **Step 4: Implementar `cancelar`**

```java
    /** Estados que una cancelación de serie nunca toca. */
    private static final Set<EstadoReserva> ESTADOS_INTOCABLES = Set.of(
            EstadoReserva.FINALIZADA, EstadoReserva.AUSENTE,
            EstadoReserva.CANCELADA, EstadoReserva.CANCELADA_PRERESERVA);

    /**
     * Da de baja la serie desde una fecha en adelante (por defecto, desde ahora).
     *
     * <p>El corte es fechaHoraInicio > max(ahora, desde): cancelar "desde hoy" a las 21 no
     * toca el turno de hoy a las 20, que ya se jugó y hay que finalizar o marcar ausente,
     * no cancelar.
     *
     * <p>No toma el lock pesimista de la cancha: cancelar no crea solapamientos. El @Version
     * de cada Reserva alcanza.
     */
    public CancelacionTurnoFijoResponse cancelar(Long id, LocalDate desde, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(turnoFijo.getCancha().getEstablecimiento(), email);

        LocalDate desdeEfectiva = desde != null ? desde : LocalDate.now();
        LocalDateTime corte = maximo(LocalDateTime.now(), desdeEfectiva.atStartOfDay());

        List<Reserva> canceladas = new ArrayList<>();
        List<CancelacionTurnoFijoResponse.OcurrenciaOmitida> omitidas = new ArrayList<>();

        for (Reserva ocurrencia : reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id)) {
            if (!ocurrencia.getFechaHoraInicio().isAfter(corte)) {
                continue;   // ya pasó o queda antes del corte pedido: no es parte de la baja
            }
            if (ESTADOS_INTOCABLES.contains(ocurrencia.getEstado())) {
                omitidas.add(new CancelacionTurnoFijoResponse.OcurrenciaOmitida(
                        ocurrencia.getFechaHoraInicio(), ocurrencia.getEstado().name()));
                continue;
            }
            ocurrencia.setEstado(EstadoReserva.CANCELADA);
            canceladas.add(ocurrencia);
        }

        reservaRepository.saveAll(canceladas);

        turnoFijo.setEstado(EstadoTurnoFijo.CANCELADO);
        turnoFijo.setCanceladoDesde(desdeEfectiva);
        turnoFijoRepository.save(turnoFijo);

        log.info("Turno fijo {} cancelado desde {}. {} ocurrencias dadas de baja, {} omitidas",
                id, desdeEfectiva, canceladas.size(), omitidas.size());

        if (!canceladas.isEmpty()) {
            eventPublisher.publishEvent(new TurnoFijoCanceladoEvent(
                    id, canceladas.stream().map(Reserva::getId).toList()));
        }

        return new CancelacionTurnoFijoResponse(canceladas.size(), omitidas);
    }

    private LocalDateTime maximo(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }
```

- [ ] **Step 5: Endpoint**

```java
    @PostMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<CancelacionTurnoFijoResponse> cancelar(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @RequestBody(required = false) CancelarTurnoFijoRequest request) {
        LocalDate desde = request != null ? request.desde() : null;
        return ResponseEntity.ok(turnoFijoService.cancelar(id, desde, userDetails.getUsername()));
    }
```

- [ ] **Step 6: Correr y verlos pasar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: PASS.

- [ ] **Step 7: Test del listener y la plantilla**

En `ReservaNotificacionListenerTest`, un test que verifica que un `TurnoFijoCanceladoEvent` con 3 ids manda **dos** mails (jugador y dueño), no seis. Después implementá `enviarNotificacionesTurnoFijoCancelado` copiando la forma de `enviarNotificacionesTurnoFijo` (reusa `findAllByIdInConEstablecimientoYDueno` y `construirModeloTurnoFijo`), y creá `turno-fijo-cancelado.html` con `th:replace="~{layout :: email(...)}"` igual que las otras dos. Sumá el caso a `EmailRendererTest`.

- [ ] **Step 8: Suite completa y commit**

```bash
./mvnw test
git add -A
git commit -m "feat(turno-fijo): cancela la serie entera con resumen de omitidas y un solo aviso"
```

---

### Task 8: Front — página de turnos fijos

**Files:**
- Create: `src/app/panel/turnos-fijos/page.tsx`, `src/components/panel/dialogo-cancelar-turno-fijo.tsx`, `src/hooks/api/use-turnos-fijos.ts`
- Modify: `src/lib/api/endpoints/turnos-fijos.ts`, `src/lib/api/tipos/turnos-fijos.ts`, `src/lib/panel/turno-fijo.ts`, `src/lib/panel/turno-fijo.test.ts`, `src/components/panel/sidebar-panel.tsx`

**Interfaces:**
- Consumes: `GET /api/v1/turnos-fijos`, `POST /{id}/cancelar` (Tasks 6-7).
- Produces: `ocurrenciasACancelar(ocurrencias, desdeISO, ahoraISO): FechaHoraISO[]`.

- [ ] **Step 1: Escribir el test que falla**

En `src/lib/panel/turno-fijo.test.ts`:

```ts
describe("ocurrenciasACancelar", () => {
  const ocurrencias = [
    "2026-09-01T20:00:00",
    "2026-09-08T20:00:00",
    "2026-09-15T20:00:00",
  ];

  it("deja afuera las que ya pasaron", () => {
    expect(ocurrenciasACancelar(ocurrencias, "2026-09-05", "2026-09-05T10:00:00"))
      .toEqual(["2026-09-08T20:00:00", "2026-09-15T20:00:00"]);
  });

  it("respeta una fecha de corte futura", () => {
    expect(ocurrenciasACancelar(ocurrencias, "2026-09-10", "2026-09-05T10:00:00"))
      .toEqual(["2026-09-15T20:00:00"]);
  });

  it("no cancela un turno de hoy que ya empezo", () => {
    expect(ocurrenciasACancelar(ocurrencias, "2026-09-08", "2026-09-08T21:30:00"))
      .toEqual(["2026-09-15T20:00:00"]);
  });
});
```

- [ ] **Step 2: Correr y verlo fallar**

Run: `npm test -- turno-fijo`
Expected: FAIL — `ocurrenciasACancelar` no está exportada.

- [ ] **Step 3: Implementar**

```ts
/**
 * Qué ocurrencias se van a dar de baja si se cancela la serie desde `desdeISO`. Espeja el
 * corte del backend: fechaHoraInicio > max(ahora, desde a las 00:00). Se usa para que el
 * diálogo diga cuántos turnos se dan de baja ANTES de confirmar.
 *
 * No filtra por estado: el backend omite las FINALIZADA y AUSENTE y lo informa en el
 * resumen de la respuesta. Acá el conteo es del alcance del corte, no del resultado.
 */
export function ocurrenciasACancelar(
  ocurrenciasISO: string[],
  desdeISO: FechaISO,
  ahoraISO: string,
): string[] {
  const corte = ahoraISO > `${desdeISO}T00:00:00` ? ahoraISO : `${desdeISO}T00:00:00`;
  return ocurrenciasISO.filter((fecha) => fecha > corte);
}
```

- [ ] **Step 4: Correr y verlo pasar**

Run: `npm test -- turno-fijo`
Expected: PASS.

- [ ] **Step 5: Construir la página**

`/panel/turnos-fijos`: tabla con cancha, día, horario, período, cliente y próxima ocurrencia; botón "Cancelar serie" que abre `dialogo-cancelar-turno-fijo.tsx`, con un datepicker "desde" (default hoy) y el conteo en vivo de `ocurrenciasACancelar`. Al resolver, mostrá el resumen del backend: *"Se dieron de baja 12 turnos. 2 quedaron como estaban porque ya se jugaron."* Seguí el patrón de `tabla-empleados.tsx` y de los drawers de `panel/canchas`.

Agregá la entrada al `sidebar-panel.tsx`, visible con el mismo criterio que el resto de las secciones de agenda.

En `components/panel/detalle-turno.tsx`, cuando `turno.turnoFijoId` no es null, agregá una línea "Parte de un turno fijo" con un link a `/panel/turnos-fijos`. Es el otro camino de entrada: el dueño casi siempre llega al problema desde la agenda, no desde el listado.

- [ ] **Step 6: Verificar y commitear**

```bash
npm test && npm run typecheck && npm run build
git add -A
git commit -m "feat(panel): pantalla de gestion de turnos fijos con cancelacion de la serie"
```

---

## FASE 3 — Renovación y edición de cliente

### Task 9: Renovar la serie

**Files:** `TurnoFijoService.java`, `TurnoFijoController.java`, `TurnoFijoServiceTest.java`

**Interfaces:**
- Produces: `TurnoFijoService.renovar(Long id, String email): TurnoFijoResponse`.

- [ ] **Step 1: Escribir los tests que fallan**

```java
    @Test
    @DisplayName("renovar_CreaLaSerieDelAnioSiguienteConRenovadoDesde")
    void renovar_CreaLaSerieDelAnioSiguienteConRenovadoDesde() {
        turnoFijoActivo.setFechaFinPeriodo(LocalDate.of(2026, 12, 31));
        when(turnoFijoRepository.existsByRenovadoDesdeId(TURNO_FIJO_ID)).thenReturn(false);

        turnoFijoService.renovar(TURNO_FIJO_ID, EMAIL_DUENO);

        ArgumentCaptor<TurnoFijo> captor = ArgumentCaptor.forClass(TurnoFijo.class);
        verify(turnoFijoRepository, atLeastOnce()).save(captor.capture());
        TurnoFijo nueva = captor.getValue();
        assertThat(nueva.getRenovadoDesdeId()).isEqualTo(TURNO_FIJO_ID);
        assertThat(nueva.getFechaFinPeriodo()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    @DisplayName("renovar_YaRenovada_LanzaIllegalArgumentConMensajePropio")
    void renovar_YaRenovada_LanzaIllegalArgumentConMensajePropio() {
        when(turnoFijoRepository.existsByRenovadoDesdeId(TURNO_FIJO_ID)).thenReturn(true);

        assertThatThrownBy(() -> turnoFijoService.renovar(TURNO_FIJO_ID, EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya fue renovado");
    }
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: FAIL — `renovar` no existe.

- [ ] **Step 3: Implementar**

```java
    /**
     * Vuelve a cargar la misma serie para el año siguiente al de su período. Crea una serie
     * NUEVA (no muta la vieja) y pasa por el mismo camino de creación: mismas validaciones,
     * mismo lock, todo-o-nada.
     *
     * <p>El inicio es max(1 de enero del año destino, hoy). El max con hoy es lo que hace
     * que renovar tarde — en febrero, no en enero — no falle contra @FutureOrPresent ni
     * contra validarFechas; generarFechasDelPeriodo ya busca la primera ocurrencia del día
     * pedido a partir de ahí.
     */
    public TurnoFijoResponse renovar(Long id, String email) {
        TurnoFijo original = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(original.getCancha().getEstablecimiento(), email);

        // El índice único de V24 lo garantiza igual, pero sin este chequeo el segundo click
        // fallaría por solapamiento ("la cancha ya está reservada el 05/01"), que no le dice
        // nada al dueño.
        if (turnoFijoRepository.existsByRenovadoDesdeId(id)) {
            throw new IllegalArgumentException(
                    "Este turno fijo ya fue renovado. Buscá la serie del año siguiente en el listado.");
        }

        int anioDestino = original.getFechaFinPeriodo().getYear() + 1;
        LocalDate primeroDeEnero = LocalDate.of(anioDestino, 1, 1);
        LocalDate hoy = LocalDate.now();
        LocalDate inicio = primeroDeEnero.isAfter(hoy) ? primeroDeEnero : hoy;

        ReservaSemanalRequest pedido = new ReservaSemanalRequest(
                original.getCancha().getId(),
                inicio,
                LocalDate.of(anioDestino, 12, 31),
                original.getDiaSemana(),
                original.getHoraInicio(),
                original.getHoraFin(),
                original.getDeporteSeleccionado(),
                original.getJugador() != null ? original.getJugador().getId() : null,
                original.getNombreClienteManual(),
                original.getTelefonoClienteManual());

        return crearInterno(pedido, email, id);
    }
```

> Refactorizá `crear` para que delegue en un `crearInterno(request, email, renovadoDesdeId)` privado, con `crear(request, email)` llamando a `crearInterno(request, email, null)`. `crearInterno` setea `.renovadoDesdeId(renovadoDesdeId)` en el builder de la regla. No dupliques el cuerpo de la creación.

- [ ] **Step 4: Endpoint, correr y commitear**

```java
    @PostMapping("/{id}/renovar")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TurnoFijoResponse> renovar(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(turnoFijoService.renovar(id, userDetails.getUsername()));
    }
```

Run: `./mvnw test`
Expected: PASS.

```bash
git add -A
git commit -m "feat(turno-fijo): renueva la serie para el anio siguiente"
```

---

### Task 10: Editar cliente

**Files:** `TurnoFijoService.java`, `TurnoFijoController.java`, `reserva/dto/EditarClienteTurnoFijoRequest.java`, `TurnoFijoServiceTest.java`

**Interfaces:**
- Produces: `TurnoFijoService.editarCliente(Long id, EditarClienteTurnoFijoRequest, String email): TurnoFijoResponse`.

- [ ] **Step 1: Escribir los tests que fallan**

```java
    @Test
    @DisplayName("editarCliente_PropagaSoloALasOcurrenciasFuturasVivas")
    void editarCliente_PropagaSoloALasOcurrenciasFuturasVivas() {
        Reserva pasada = ocurrencia(LocalDateTime.now().minusDays(7), EstadoReserva.FINALIZADA);
        Reserva futura = ocurrencia(LocalDateTime.now().plusDays(7), EstadoReserva.CONFIRMADA);
        pasada.setNombreClienteManual("Grupo del Colo");
        futura.setNombreClienteManual("Grupo del Colo");
        when(reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(TURNO_FIJO_ID))
                .thenReturn(List.of(pasada, futura));

        turnoFijoService.editarCliente(TURNO_FIJO_ID,
                new EditarClienteTurnoFijoRequest("Grupo del Colorado", "11 6666-7777"), EMAIL_DUENO);

        assertThat(pasada.getNombreClienteManual()).isEqualTo("Grupo del Colo");
        assertThat(futura.getNombreClienteManual()).isEqualTo("Grupo del Colorado");
    }

    @Test
    @DisplayName("editarCliente_SobreSerieDeJugador_LanzaIllegalArgument")
    void editarCliente_SobreSerieDeJugador_LanzaIllegalArgument() {
        turnoFijoActivo.setJugador(jugadorRegistrado);

        assertThatThrownBy(() -> turnoFijoService.editarCliente(TURNO_FIJO_ID,
                new EditarClienteTurnoFijoRequest("Otro", null), EMAIL_DUENO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jugador registrado");
    }
```

- [ ] **Step 2: Correr y verlos fallar**

Run: `./mvnw test -Dtest=TurnoFijoServiceTest`
Expected: FAIL — `editarCliente` no existe.

- [ ] **Step 3: Implementar**

```java
package com.matiasmeira.sacaladelangulo.reserva.dto;

import jakarta.validation.constraints.NotBlank;

public record EditarClienteTurnoFijoRequest(
        @NotBlank(message = "El nombre del cliente es obligatorio")
        String nombre,
        String telefono
) {
}
```

```java
    /**
     * Corrige a quién figura la serie. Sólo en series de mostrador: si la serie está atada a
     * un jugador registrado, el nombre sale de su cuenta y no es un campo editable acá.
     *
     * <p>Propaga sólo a las ocurrencias futuras y vivas. Las pasadas son registro de lo que
     * ocurrió y no se reescriben.
     */
    public TurnoFijoResponse editarCliente(Long id, EditarClienteTurnoFijoRequest request, String email) {
        TurnoFijo turnoFijo = buscarTurnoFijo(id);
        autorizacionEmpleadoService.validarPropietarioOAdmin(turnoFijo.getCancha().getEstablecimiento(), email);

        if (turnoFijo.getJugador() != null) {
            throw new IllegalArgumentException(
                    "Este turno fijo está a nombre de un jugador registrado: el nombre sale de su cuenta.");
        }

        turnoFijo.setNombreClienteManual(request.nombre());
        turnoFijo.setTelefonoClienteManual(request.telefono());
        turnoFijoRepository.save(turnoFijo);

        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> aActualizar = reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(id).stream()
                .filter(r -> r.getFechaHoraInicio().isAfter(ahora))
                .filter(r -> r.getEstado() == EstadoReserva.CONFIRMADA
                          || r.getEstado() == EstadoReserva.PENDIENTE_SENA)
                .peek(r -> {
                    r.setNombreClienteManual(request.nombre());
                    r.setTelefonoClienteManual(request.telefono());
                })
                .toList();
        reservaRepository.saveAll(aActualizar);

        return turnoFijoMapper.mapToResponse(turnoFijo, List.of());
    }
```

- [ ] **Step 4: Endpoint, correr y commitear**

```java
    @PatchMapping("/{id}/cliente")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<TurnoFijoResponse> editarCliente(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @RequestBody @Valid EditarClienteTurnoFijoRequest request) {
        return ResponseEntity.ok(turnoFijoService.editarCliente(id, request, userDetails.getUsername()));
    }
```

Run: `./mvnw test`
Expected: PASS.

```bash
git add -A
git commit -m "feat(turno-fijo): permite corregir el cliente de una serie de mostrador"
```

---

### Task 11: Front — renovar y editar cliente

**Files:** `src/app/panel/turnos-fijos/page.tsx`, `src/hooks/api/use-turnos-fijos.ts`, `src/lib/api/endpoints/turnos-fijos.ts`, `src/lib/panel/turno-fijo.ts`, `src/lib/panel/turno-fijo.test.ts`

**Interfaces:**
- Produces: `inicioDeRenovacion(fechaFinPeriodoISO, hoyISO): FechaISO`.

- [ ] **Step 1: Escribir el test que falla**

```ts
describe("inicioDeRenovacion", () => {
  it("arranca el 1 de enero del anio siguiente", () => {
    expect(inicioDeRenovacion("2026-12-31", "2026-09-06")).toBe("2027-01-01");
  });

  it("arranca hoy si el 1 de enero ya paso", () => {
    expect(inicioDeRenovacion("2026-12-31", "2027-02-10")).toBe("2027-02-10");
  });
});
```

- [ ] **Step 2: Correr y verlo fallar**

Run: `npm test -- turno-fijo`
Expected: FAIL — `inicioDeRenovacion` no existe.

- [ ] **Step 3: Implementar**

```ts
/**
 * Desde cuándo arranca la serie renovada. Espeja TurnoFijoService.renovar: el 1 de enero del
 * año siguiente, o hoy si ese 1 de enero ya pasó (renovar en febrero no puede pedir fechas
 * pasadas, que el backend rechaza).
 */
export function inicioDeRenovacion(fechaFinPeriodoISO: FechaISO, hoyISO: FechaISO): FechaISO {
  const primeroDeEnero = `${Number(fechaFinPeriodoISO.slice(0, 4)) + 1}-01-01`;
  return primeroDeEnero > hoyISO ? primeroDeEnero : hoyISO;
}
```

- [ ] **Step 4: Correr, cablear la UI y commitear**

Botón "Renovar" en cada fila del listado, que muestra el período resultante antes de confirmar (`inicioDeRenovacion` → 31/12 del año destino) y desaparece si la serie ya fue renovada (`renovadoDesdeId` de otra fila, o el 400 del backend). Botón "Editar cliente" sólo cuando `jugadorId` es null.

```bash
npm test && npm run typecheck && npm run build
git add -A
git commit -m "feat(panel): renovacion y edicion de cliente de un turno fijo"
```

---

## Verificación final

- [ ] `./mvnw test` verde en el back.
- [ ] `npm test && npm run typecheck && npm run build` verde en el front, con el lint en el baseline conocido (2 errores y 2 warnings en `wizard-onboarding`).
- [ ] Levantar la app desde el IDE contra Postgres para que Flyway aplique **V23 y V24** — ninguna de las dos corrió nunca. Si V24 falla, es casi seguro uno de los tres CHECK: revisá `chk_turnos_fijos_cancelacion` contra los datos existentes (no debería haber, la tabla es nueva).
- [ ] Probar a mano el recorrido completo: crear un turno fijo, verlo en la agenda con el badge, verlo en el listado, cancelarlo desde una fecha, y renovarlo.
