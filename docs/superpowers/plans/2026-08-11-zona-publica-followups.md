# Zona Pública — Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close three gaps deliberately parked by the final review of the "zona pública" feature (`docs/superpowers/plans/2026-08-07-zona-publica-marketplace.md`): `precioDesde`/`senaDesde` not falling back to `precioBase`, the fecha/hora search filter ignoring horarios/días no laborables/bloqueos, and the no-location listing having no row cap.

**Architecture:** All three fixes live in `ComplejoPublicoService` plus its immediate repository dependencies. Goal 2's trickiest piece (overnight-crossing horario math) is extracted out of `DisponibilidadService` into a new small pure-calculation class (`HorarioAtencionCalculator`, same pattern as the existing `GeoUtils`/`PoolCanchaCalculator`) so both services share one implementation instead of risking two that drift apart.

**Tech Stack:** Spring Boot 3.5.14, Java 21, Spring Data JPA (Hibernate), H2 (tests), JUnit 5 + Mockito.

**Spec:** `docs/superpowers/specs/2026-08-11-zona-publica-followups-design.md` — read for full rationale; this plan implements it as written.

## Global Constraints

- Match existing code style exactly (Lombok, Spanish naming, existing comment conventions, existing JPQL/repository idioms in this codebase).
- `./mvnw test` (the default, no-Docker suite) must stay green throughout.
- `Cancha.preciosPorDuracion`/`Tarifa.preciosPorDuracion` are explicitly OUT of scope for the price fallback (spec Goal 1) — do not add them as price candidates.
- The `HorarioAtencionCalculator` extraction from `DisponibilidadService` (Task 2) MUST be behavior-preserving — `DisponibilidadServiceTest` must pass unmodified, with no changes to that test file.
- No true DB-side aggregate/rating pagination for the no-location listing (spec Goal 3) — a fixed row cap via `Pageable` is the whole scope of that fix.
- Don't touch the authenticated app's flows (`ReservaService`, `PrecioReservaCalculator`, the owner-facing `DisponibilidadController`) beyond the pure extraction in Task 2.

---

## Task 1: `precioDesde`/`senaDesde` fall back to `precioBase`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Produces: `ComplejoPublicoService.precioMinimoDeCancha(Cancha): BigDecimal` (private helper) — used at all three "starting price" call sites in this same class. No change to any public method signature.

- [ ] **Step 1: Write the failing tests**

The existing test helper `canchaConTarifa` (already in `ComplejoPublicoServiceTest.java`) hardcodes `precioBase(BigDecimal.valueOf(1000))`, which is LOWER than every tarifa price the existing tests use (3000, 5000) — once the fallback lands, `precioBase` would win every existing assertion and break them. Bump that constant so it stays out of the way of existing tarifa-based assertions, and add dedicated new tests for the fallback itself.

Change this line inside `canchaConTarifa`:
```java
                .precioBase(BigDecimal.valueOf(1000))
```
to:
```java
                .precioBase(BigDecimal.valueOf(10000))
```
(10000 is higher than every tarifa price used anywhere in this file today — 3000 and 5000 — so every existing assertion that expects a tarifa-derived number is unaffected.)

Then add these new tests (place them right after the existing `buscarComplejos_ConFiltroDeDeporte_AcotaPrecioDesdeYSenaDesdeALasCanchasDeEseDeporte` test):

```java
    @Test
    @DisplayName("buscarComplejos_CanchaSinTarifas_PrecioDesdeCaeAPrecioBase")
    void buscarComplejos_CanchaSinTarifas_PrecioDesdeCaeAPrecioBase() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha sinTarifas = Cancha.builder()
                .id(10L)
                .nombre("Cancha 10")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(4000))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(est)
                .build();

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(sinTarifas));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        ComplejoCardResponse card = resultado.getContent().get(0);
        assertEquals(BigDecimal.valueOf(4000), card.precioDesde());
        assertEquals(BigDecimal.valueOf(500), card.senaDesde());
    }

    @Test
    @DisplayName("buscarComplejos_TarifaMasCaraQuePrecioBase_PrecioDesdeUsaPrecioBase")
    void buscarComplejos_TarifaMasCaraQuePrecioBase_PrecioDesdeUsaPrecioBase() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", true);
        Cancha cancha = Cancha.builder()
                .id(10L)
                .nombre("Cancha 10")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(2000))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(est)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(9000))
                .build()));

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L)))
                .thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, null, null, PageRequest.of(0, 20));

        // La tarifa (9000) es más cara que precioBase (2000): precioDesde tiene que reflejar
        // el mínimo real, no solo el precio de tarifa.
        assertEquals(BigDecimal.valueOf(2000), resultado.getContent().get(0).precioDesde());
    }
```

(These two tests mock `findActivosPorDeporte` with a plain `null` argument, matching every other test in this file at this point — `findActivosPorDeporte` is still single-argument here. Task 5, later in this plan, adds a `Pageable` parameter to that method and sweeps through updating every mock in this file, including these two, to match.)

- [ ] **Step 2: Run to see the new tests fail**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: FAIL — both new tests fail because `precioDesde`/behavior doesn't yet fall back to `precioBase` (the first new test would see `precioDesde` as `null` since the cancha has no tarifas; the second would see `9000` instead of `2000`).

- [ ] **Step 3: Implement the fallback**

Add this import to `ComplejoPublicoService.java` (alongside the existing `java.util.stream.Collectors` import):
```java
import java.util.stream.Stream;
```

Add this private helper method (place it right after `construirCard`, before `paginarEnMemoria`):
```java
    /**
     * Precio mínimo que puede llegar a cobrar esta cancha: el menor entre sus
     * Tarifa.precio configuradas y su precioBase (que PrecioReservaCalculator ya usa
     * como fallback cuando ninguna Tarifa matchea una reserva puntual). precioBase es
     * NOT NULL en el modelo, así que esto nunca devuelve null -- a diferencia de antes,
     * cuando una cancha sin tarifas no aportaba ningún candidato y precioDesde podía
     * quedar en null pese a ser reservable.
     */
    private BigDecimal precioMinimoDeCancha(Cancha cancha) {
        return Stream.concat(cancha.getTarifas().stream().map(Tarifa::getPrecio), Stream.of(cancha.getPrecioBase()))
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }
```

Change `construirCard`'s `precioDesde` computation from:
```java
        BigDecimal precioDesde = relevantes.stream()
                .flatMap(c -> c.getTarifas().stream())
                .map(Tarifa::getPrecio)
                .min(Comparator.naturalOrder())
                .orElse(null);
```
to:
```java
        BigDecimal precioDesde = relevantes.stream()
                .map(this::precioMinimoDeCancha)
                .min(Comparator.naturalOrder())
                .orElse(null);
```

Change `obtenerDetalle`'s complejo-level `precioDesde` computation (the one right after `Set<Deporte> deportes = ...`) the same way — from:
```java
        BigDecimal precioDesde = canchas.stream()
                .flatMap(c -> c.getTarifas().stream())
                .map(Tarifa::getPrecio)
                .min(Comparator.naturalOrder())
                .orElse(null);
```
to:
```java
        BigDecimal precioDesde = canchas.stream()
                .map(this::precioMinimoDeCancha)
                .min(Comparator.naturalOrder())
                .orElse(null);
```

Change the per-cancha price inside `obtenerDetalle`'s `CanchaPublicaDto` construction from:
```java
        List<CanchaPublicaDto> canchasPublicas = canchas.stream()
                .map(c -> new CanchaPublicaDto(
                        c.getId(),
                        c.getNombre(),
                        Set.copyOf(c.getDeportes()),
                        c.getTarifas().stream().map(Tarifa::getPrecio).min(Comparator.naturalOrder()).orElse(null)))
                .toList();
```
to:
```java
        List<CanchaPublicaDto> canchasPublicas = canchas.stream()
                .map(c -> new CanchaPublicaDto(
                        c.getId(),
                        c.getNombre(),
                        Set.copyOf(c.getDeportes()),
                        precioMinimoDeCancha(c)))
                .toList();
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS (all tests, old and new — the old ones because of the `precioBase(10000)` bump in Step 1, the new ones because of Steps 3's fallback logic).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "fix: precioDesde/senaDesde caen a precioBase cuando una cancha no tiene tarifas"
```

---

## Task 2: Extract `HorarioAtencionCalculator`, refactor `DisponibilidadService` onto it

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/HorarioAtencionCalculator.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/HorarioAtencionCalculatorTest.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadService.java`

**Interfaces:**
- Produces: `HorarioAtencionCalculator.calcularVentana(HorarioAtencion, LocalDate): HorarioAtencionCalculator.VentanaHoraria` (record with `inicio()`/`fin()` accessors, both `LocalDateTime`) — consumed by `DisponibilidadService` (this task) and by `ComplejoPublicoService` (Task 4).

**This task must not change any observable behavior of `DisponibilidadService`.** It relocates four lines of existing logic into a new, independently-testable class and calls the new class from the same spot. `DisponibilidadServiceTest` is not touched and must still pass.

- [ ] **Step 1: Write the failing test for the new class**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HorarioAtencionCalculator - Resolución de ventana horaria por fecha")
class HorarioAtencionCalculatorTest {

    @Test
    @DisplayName("calcularVentana_HorarioMismoDia_DevuelveInicioYFinEnLaMismaFecha")
    void calcularVentana_HorarioMismoDia_DevuelveInicioYFinEnLaMismaFecha() {
        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build();
        LocalDate fecha = LocalDate.of(2026, 8, 10);

        HorarioAtencionCalculator.VentanaHoraria ventana = HorarioAtencionCalculator.calcularVentana(horario, fecha);

        assertEquals(LocalDateTime.of(2026, 8, 10, 9, 0), ventana.inicio());
        assertEquals(LocalDateTime.of(2026, 8, 10, 23, 0), ventana.fin());
    }

    @Test
    @DisplayName("calcularVentana_HorarioCruzaMedianoche_FinCaeEnElDiaSiguiente")
    void calcularVentana_HorarioCruzaMedianoche_FinCaeEnElDiaSiguiente() {
        HorarioAtencion horario = HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(20, 0))
                .horaCierre(LocalTime.of(2, 0))
                .build();
        LocalDate fecha = LocalDate.of(2026, 8, 10);

        HorarioAtencionCalculator.VentanaHoraria ventana = HorarioAtencionCalculator.calcularVentana(horario, fecha);

        assertEquals(LocalDateTime.of(2026, 8, 10, 20, 0), ventana.inicio());
        assertEquals(LocalDateTime.of(2026, 8, 11, 2, 0), ventana.fin());
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=HorarioAtencionCalculatorTest`
Expected: compile error — `HorarioAtencionCalculator` doesn't exist yet.

- [ ] **Step 3: Implement `HorarioAtencionCalculator`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cálculo puro (sin acceso a base de datos) de la ventana horaria concreta que resulta de
 * aplicar un HorarioAtencion a una fecha puntual. Maneja el caso de un horario que cierra
 * después de medianoche (cierre < apertura -> el cierre cae calendáricamente al día
 * siguiente). Extraído de DisponibilidadService para que ComplejoPublicoService (filtro de
 * disponibilidad en la búsqueda pública) también lo use, en vez de tener dos
 * implementaciones de este cálculo que puedan desincronizarse.
 */
public final class HorarioAtencionCalculator {

    private HorarioAtencionCalculator() {
    }

    public record VentanaHoraria(LocalDateTime inicio, LocalDateTime fin) {
    }

    public static VentanaHoraria calcularVentana(HorarioAtencion horario, LocalDate fecha) {
        LocalDateTime inicio = fecha.atTime(horario.getHoraApertura());
        LocalDateTime fin = horario.getHoraCierre().isBefore(horario.getHoraApertura())
                ? fecha.plusDays(1).atTime(horario.getHoraCierre())
                : fecha.atTime(horario.getHoraCierre());
        return new VentanaHoraria(inicio, fin);
    }
}
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=HorarioAtencionCalculatorTest`
Expected: PASS.

- [ ] **Step 5: Refactor `DisponibilidadService` to use it (no behavior change)**

In `DisponibilidadService.java`, inside `calcularDisponibilidadDelDia`, change:
```java
        HorarioAtencion horario = horarioOpt.get();
        LocalDateTime ventanaInicio = fecha.atTime(horario.getHoraApertura());
        LocalDateTime ventanaFin = horario.getHoraCierre().isBefore(horario.getHoraApertura())
                ? fecha.plusDays(1).atTime(horario.getHoraCierre())
                : fecha.atTime(horario.getHoraCierre());
```
to:
```java
        HorarioAtencion horario = horarioOpt.get();
        com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator.VentanaHoraria ventana =
                com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator.calcularVentana(horario, fecha);
        LocalDateTime ventanaInicio = ventana.inicio();
        LocalDateTime ventanaFin = ventana.fin();
```

(Using the fully-qualified name inline rather than adding a top-level import keeps this diff a pure one-hunk substitution — either is fine stylistically in this codebase, but inline avoids touching the import block at all, making the "this is a pure relocation, nothing else changed" property visually obvious in the diff. If you prefer, add `import com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator;` at the top and drop the fully-qualified prefix in the body instead — behaviorally identical, pick whichever you find cleaner.)

The rest of `calcularDisponibilidadDelDia` (the día-no-laborable check above this block, and everything using `ventanaInicio`/`ventanaFin` below it) is untouched.

- [ ] **Step 6: Run `DisponibilidadServiceTest` to confirm zero behavior change**

Run: `./mvnw test -Dtest=DisponibilidadServiceTest`
Expected: PASS — same tests, same assertions, no test file changes. If anything fails here, the refactor introduced a behavior change; stop and fix before proceeding (do not adjust `DisponibilidadServiceTest` to match new behavior — the whole point of this task is that there is no new behavior).

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/HorarioAtencionCalculator.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/HorarioAtencionCalculatorTest.java src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadService.java
git commit -m "refactor: extraer HorarioAtencionCalculator de DisponibilidadService (sin cambio de comportamiento)"
```

---

## Task 3: Batch repository queries for días no laborables and bloqueos

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/DiaNoLaborableRepository.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/BloqueoCanchaRepository.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/DiaNoLaborableRepositoryTest.java`
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/BloqueoCanchaRepositoryTest.java`

**Interfaces:**
- Produces: `DiaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List<Long>, LocalDate): List<DiaNoLaborable>`, `BloqueoCanchaRepository.findByEstablecimientoIdInAndRango(List<Long>, LocalDateTime, LocalDateTime): List<BloqueoCancha>`, `EstablecimientoRepository.precargarHorarios(List<Long>): List<Establecimiento>` — all consumed by Task 4.

- [ ] **Step 1: Write the failing test for `DiaNoLaborableRepository`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("DiaNoLaborableRepository - Consulta en lote por fecha puntual")
class DiaNoLaborableRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DiaNoLaborableRepository diaNoLaborableRepository;

    private Establecimiento establecimiento(Usuario dueno, String slug) {
        return entityManager.persist(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle " + slug)
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    @Test
    @DisplayName("findByEstablecimientoIdInAndFecha_DevuelveSoloLosQueTienenEsaFechaMarcada")
    void findByEstablecimientoIdInAndFecha_DevuelveSoloLosQueTienenEsaFechaMarcada() {
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

        Establecimiento conFeriado = establecimiento(dueno, "con-feriado");
        Establecimiento sinFeriado = establecimiento(dueno, "sin-feriado");

        LocalDate fecha = LocalDate.of(2026, 12, 25);
        entityManager.persist(DiaNoLaborable.builder()
                .fecha(fecha)
                .motivo("Feriado")
                .establecimiento(conFeriado)
                .build());
        entityManager.flush();

        List<DiaNoLaborable> resultado = diaNoLaborableRepository
                .findByEstablecimientoIdInAndFecha(List.of(conFeriado.getId(), sinFeriado.getId()), fecha);

        assertEquals(1, resultado.size());
        assertEquals(conFeriado.getId(), resultado.get(0).getEstablecimiento().getId());
    }
}
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=DiaNoLaborableRepositoryTest`
Expected: compile error — `findByEstablecimientoIdInAndFecha` doesn't exist yet.

- [ ] **Step 3: Implement it**

Add to `DiaNoLaborableRepository.java`:
```java
    /**
     * Variante en lote para el filtro de disponibilidad de la búsqueda pública (ver
     * ComplejoPublicoService.filtrarPorDisponibilidad): consulta todos los complejos
     * candidatos de una sola vez para una fecha puntual, en vez de una consulta por
     * complejo.
     */
    List<DiaNoLaborable> findByEstablecimientoIdInAndFecha(List<Long> establecimientoIds, LocalDate fecha);
```
(Add `import java.util.List;` if not already present in that file — it already is.)

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=DiaNoLaborableRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Write the failing test for `BloqueoCanchaRepository`**

```java
package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("BloqueoCanchaRepository - Consulta en lote por rango, a través de varios establecimientos")
class BloqueoCanchaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Test
    @DisplayName("findByEstablecimientoIdInAndRango_DevuelveSoloLosBloqueosQueSeSuperponen")
    void findByEstablecimientoIdInAndRango_DevuelveSoloLosBloqueosQueSeSuperponen() {
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
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        BloqueoCancha superpuesto = entityManager.persist(BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 9, 0))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 11, 0))
                .motivo("Mantenimiento")
                .build());
        entityManager.persist(BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 15, 0))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 16, 0))
                .motivo("No se superpone")
                .build());
        entityManager.flush();

        List<BloqueoCancha> resultado = bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(
                List.of(establecimiento.getId()),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 10, 11, 0));

        assertEquals(1, resultado.size());
        assertEquals(superpuesto.getId(), resultado.get(0).getId());
    }
}
```

- [ ] **Step 6: Run to see it fail**

Run: `./mvnw test -Dtest=BloqueoCanchaRepositoryTest`
Expected: compile error — `findByEstablecimientoIdInAndRango` doesn't exist yet.

- [ ] **Step 7: Implement it**

Add to `BloqueoCanchaRepository.java`:
```java
    /**
     * Variante en lote de findByEstablecimientoAndRango para el filtro de disponibilidad
     * de la búsqueda pública: trae los bloqueos de todos los complejos candidatos en una
     * sola consulta.
     */
    @Query("SELECT b FROM BloqueoCancha b WHERE b.cancha.establecimiento.id IN :establecimientoIds AND " +
           "(b.fechaInicio < :fin AND b.fechaFin > :inicio)")
    List<BloqueoCancha> findByEstablecimientoIdInAndRango(@Param("establecimientoIds") List<Long> establecimientoIds,
                                                           @Param("inicio") LocalDateTime inicio,
                                                           @Param("fin") LocalDateTime fin);
```

- [ ] **Step 8: Run to see it pass**

Run: `./mvnw test -Dtest=BloqueoCanchaRepositoryTest`
Expected: PASS.

- [ ] **Step 9: Add `precargarHorarios` to `EstablecimientoRepository`**

This one reuses the exact same "session priming" pattern as the existing `precargarFotos` in the same file (already proven correct by that method's own usage in `ComplejoPublicoService`/Task 2 of the original plan) — no dedicated repository test needed here, consistent with how `precargarFotos` itself wasn't separately repository-tested; its correctness is exercised through Task 4's service-level tests in this plan.

Add to `EstablecimientoRepository.java`:
```java
    /**
     * Trae, para el lote de ids indicado, los horarios de atención ya inicializados en la
     * misma consulta: evita un SELECT de horarios por establecimiento al filtrar
     * candidatos por fecha/hora en la búsqueda pública (ver
     * ComplejoPublicoService.filtrarPorDisponibilidad). Mismo patrón de "precarga por
     * efecto" que precargarFotos: dentro de la misma transacción, las entidades que
     * devuelve son las mismas instancias que ya tiene el caller.
     */
    @EntityGraph(attributePaths = {"horariosAtencion"})
    @Query("SELECT e FROM Establecimiento e WHERE e.id IN :ids")
    List<Establecimiento> precargarHorarios(@Param("ids") List<Long> ids);
```

- [ ] **Step 10: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/DiaNoLaborableRepository.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/BloqueoCanchaRepository.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/DiaNoLaborableRepositoryTest.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/BloqueoCanchaRepositoryTest.java
git commit -m "feat: consultas en lote de dias no laborables, bloqueos y horarios de atencion"
```

---

## Task 4: Wire horarios, días no laborables, and bloqueos into `filtrarPorDisponibilidad`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Consumes: `HorarioAtencionCalculator.calcularVentana` (Task 2), `DiaNoLaborableRepository.findByEstablecimientoIdInAndFecha`, `BloqueoCanchaRepository.findByEstablecimientoIdInAndRango`, `EstablecimientoRepository.precargarHorarios` (Task 3).

- [ ] **Step 1: Write the failing tests**

Add these imports to `ComplejoPublicoServiceTest.java`: `com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha`, `com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable`, `com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion`, `com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository`, `com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository`.

Add two new `@Mock` fields to the test class (alongside the existing ones):
```java
    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;
```

Add these test methods (place them after `buscarComplejos_PageMuyGrande_NoLanzaExcepcionYDevuelveVacio`):

```java
    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_IncluyeComplejoAbiertoConCanchaLibre")
    void buscarComplejos_ConFechaYHora_IncluyeComplejoAbiertoConCanchaLibre() {
        // 2026-08-10 es lunes.
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeComplejoSinHorarioAtencionEseDia")
    void buscarComplejos_ConFechaYHora_ExcluyeComplejoSinHorarioAtencionEseDia() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of()); // sin horarios cargados para ningún día

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeComplejoConDiaNoLaborable")
    void buscarComplejos_ConFechaYHora_ExcluyeComplejoConDiaNoLaborable() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);
        DiaNoLaborable feriado = DiaNoLaborable.builder().fecha(fecha).motivo("Feriado").establecimiento(est).build();

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of(feriado));

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeCanchaConBloqueoAunSinReservaSuperpuesta")
    void buscarComplejos_ConFechaYHora_ExcluyeCanchaConBloqueoAunSinReservaSuperpuesta() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();
        BloqueoCancha bloqueo = BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 9, 30))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 12, 0))
                .motivo("Mantenimiento")
                .build();

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(10, 0);

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of(bloqueo));
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_ExcluyeSiLaVentanaSolicitadaNoEntraCompletaEnElHorario")
    void buscarComplejos_ConFechaYHora_ExcluyeSiLaVentanaSolicitadaNoEntraCompletaEnElHorario() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(22, 0)) // cierra a las 22:00
                .build()));

        LocalDate fecha = LocalDate.of(2026, 8, 10);
        LocalTime hora = LocalTime.of(21, 30); // + 60 min de ventana = termina 22:30, después del cierre

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("buscarComplejos_ConFechaYHora_IncluyeHorarioQueCruzaMedianocheSiLaVentanaEntra")
    void buscarComplejos_ConFechaYHora_IncluyeHorarioQueCruzaMedianocheSiLaVentanaEntra() {
        Establecimiento est = establecimiento(1L, "complejo-uno", "Complejo Uno", false);
        est.setHorariosAtencion(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(20, 0))
                .horaCierre(LocalTime.of(2, 0)) // cruza medianoche
                .build()));
        Cancha cancha = Cancha.builder()
                .id(10L).nombre("Cancha 10").deportes(Set.of(Deporte.FUTBOL)).capacidad(10).isActive(true)
                .precioBase(BigDecimal.valueOf(1000)).montoSena(BigDecimal.valueOf(200)).establecimiento(est).build();

        LocalDate fecha = LocalDate.of(2026, 8, 10); // lunes
        LocalTime hora = LocalTime.of(23, 0); // + 60 min = 00:00 del martes, sigue dentro de la ventana (hasta las 02:00)

        when(establecimientoRepository.findActivosPorDeporte(null)).thenReturn(List.of(est));
        when(establecimientoRepository.precargarHorarios(List.of(1L))).thenReturn(List.of(est));
        when(canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(List.of(1L))).thenReturn(List.of(cancha));
        when(reservaRepository.findCanchaIdsConSolapamiento(eq(List.of(10L)), any(), any())).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(eq(List.of(1L)), any(), any())).thenReturn(List.of());
        when(diaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List.of(1L), fecha)).thenReturn(List.of());
        when(canchaRepository.findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(1L))).thenReturn(List.of(cancha));
        when(establecimientoRepository.precargarFotos(List.of(1L))).thenReturn(List.of(est));
        when(feedbackRepository.calcularPromediosPorEstablecimientos(List.of(1L))).thenReturn(List.of());
        when(feedbackRepository.contarPorEstablecimientos(List.of(1L))).thenReturn(List.of());

        Page<ComplejoCardResponse> resultado = complejoPublicoService.buscarComplejos(
                null, null, null, null, fecha, hora, PageRequest.of(0, 20));

        assertEquals(1, resultado.getTotalElements());
    }
```

Add `import static org.mockito.ArgumentMatchers.any;` usage is already present as an import in the file (confirm; if missing, add `import static org.mockito.ArgumentMatchers.any;`).

- [ ] **Step 2: Run to see the new tests fail**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: compile errors (missing mocks/methods) or assertion failures — `filtrarPorDisponibilidad` doesn't check horarios/días no laborables/bloqueos yet.

- [ ] **Step 3: Wire the new fields and logic into `ComplejoPublicoService`**

Add two new constructor-injected fields (Lombok `@RequiredArgsConstructor` picks them up automatically) right after the existing `feedbackRepository` field:
```java
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;
```

Add these imports:
```java
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator;
```

Replace `filtrarPorDisponibilidad` entirely:
```java
    private List<Establecimiento> filtrarPorDisponibilidad(List<Establecimiento> candidatos, Deporte deporte, LocalDate fecha, LocalTime hora) {
        List<Long> establecimientoIds = candidatos.stream().map(Establecimiento::getId).toList();
        establecimientoRepository.precargarHorarios(establecimientoIds);

        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdInAndIsActiveTrue(establecimientoIds);
        if (deporte != null) {
            canchas = canchas.stream().filter(c -> c.getDeportes().contains(deporte)).toList();
        }

        Map<Long, List<Cancha>> canchasPorEstablecimiento = canchas.stream()
                .collect(Collectors.groupingBy(c -> c.getEstablecimiento().getId()));

        LocalDateTime inicioReserva = LocalDateTime.of(fecha, hora);
        LocalDateTime finReserva = inicioReserva.plusMinutes(VENTANA_DISPONIBILIDAD_MINUTOS);

        List<Long> canchaIds = canchas.stream().map(Cancha::getId).toList();
        Set<Long> canchasNoDisponibles = canchaIds.isEmpty()
                ? Set.of()
                : new HashSet<>(reservaRepository.findCanchaIdsConSolapamiento(canchaIds, inicioReserva, finReserva));
        if (!canchaIds.isEmpty()) {
            bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(establecimientoIds, inicioReserva, finReserva).stream()
                    .map(b -> b.getCancha().getId())
                    .forEach(canchasNoDisponibles::add);
        }

        Set<Long> establecimientosNoLaborables = diaNoLaborableRepository
                .findByEstablecimientoIdInAndFecha(establecimientoIds, fecha).stream()
                .map(d -> d.getEstablecimiento().getId())
                .collect(Collectors.toSet());

        return candidatos.stream()
                .filter(est -> !establecimientosNoLaborables.contains(est.getId()))
                .filter(est -> estaAbiertoEnVentana(est, fecha, inicioReserva, finReserva))
                .filter(est -> canchasPorEstablecimiento.getOrDefault(est.getId(), List.of()).stream()
                        .anyMatch(c -> !canchasNoDisponibles.contains(c.getId())))
                .toList();
    }

    /**
     * ¿El complejo tiene un HorarioAtencion para el día de la semana de "fecha" que cubra
     * por completo la ventana [inicioReserva, finReserva)? Mismo criterio que
     * DisponibilidadService.generarSlotsLibres: el turno completo tiene que entrar en el
     * horario, no solo su inicio.
     */
    private boolean estaAbiertoEnVentana(Establecimiento establecimiento, LocalDate fecha, LocalDateTime inicioReserva, LocalDateTime finReserva) {
        return establecimiento.getHorariosAtencion().stream()
                .filter(h -> h.getDiaSemana() == fecha.getDayOfWeek())
                .findFirst()
                .map(horario -> HorarioAtencionCalculator.calcularVentana(horario, fecha))
                .map(ventana -> !inicioReserva.isBefore(ventana.inicio()) && !finReserva.isAfter(ventana.fin()))
                .orElse(false);
    }
```

- [ ] **Step 4: Run to see it pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS (all tests, old and new).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "fix: la busqueda por fecha/hora respeta horarios, dias no laborables y bloqueos"
```

---

## Task 5: Cap the no-location listing query

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java`

**Interfaces:**
- Produces: `EstablecimientoRepository.findActivosPorDeporte(Deporte, Pageable): List<Establecimiento>` (signature change — adds a required `Pageable` parameter).

- [ ] **Step 1: Write the failing repository test**

Add to `EstablecimientoRepositoryTest.java` (this file already has `@DataJpaTest` set up; reuse the existing pattern):

```java
    @Test
    @DisplayName("findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas")
    void findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-cap@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        for (int i = 0; i < 3; i++) {
            entityManager.persist(Establecimiento.builder()
                    .nombre("Complejo Cap " + i)
                    .direccion("Calle " + i)
                    .slug("complejo-cap-" + i)
                    .latitud(-34.6)
                    .longitud(-58.4)
                    .requiereSena(false)
                    .isActive(true)
                    .dueno(dueno)
                    .build());
        }
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository
                .findActivosPorDeporte(null, org.springframework.data.domain.PageRequest.of(0, 2));

        assertEquals(2, resultado.size());
    }
```

- [ ] **Step 2: Run to see it fail**

Run: `./mvnw test -Dtest=EstablecimientoRepositoryTest`
Expected: compile error — `findActivosPorDeporte(Deporte, Pageable)` doesn't exist yet (current signature only takes `Deporte`).

- [ ] **Step 3: Add the `Pageable` parameter**

In `EstablecimientoRepository.java`, add `import org.springframework.data.domain.Pageable;`, then change:
```java
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes)")
    List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte);
```
to:
```java
    @Query("SELECT DISTINCT e FROM Establecimiento e LEFT JOIN Cancha c ON c.establecimiento.id = e.id AND c.isActive = true " +
           "WHERE e.isActive = true AND (:deporte IS NULL OR :deporte MEMBER OF c.deportes)")
    List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte, Pageable pageable);
```
(Also update the method's Javadoc comment just above it — the one starting "Variante de findCercanosYPorDeporte sin filtro geográfico" — by appending one sentence: `Acotada por el Pageable que le pase el caller: sin ubicación no hay límite geográfico natural, así que ComplejoPublicoService le pasa un tope fijo de filas (ver M-final-2 / Goal 3 del follow-up de zona pública).`)

- [ ] **Step 4: Run to see the repository test pass**

Run: `./mvnw test -Dtest=EstablecimientoRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Update the call site and all existing mocks in `ComplejoPublicoService`/its test**

In `ComplejoPublicoService.java`, add `import org.springframework.data.domain.PageRequest;`, add a new constant right after `RADIO_BUSQUEDA_MAXIMO_KM`:
```java
    private static final int MAX_CANDIDATOS_SIN_UBICACION = 500;
```
Change the call site in `buscarComplejos`:
```java
        List<Establecimiento> candidatos = conUbicacion
                ? establecimientoRepository.findCercanosYPorDeporte(lat, lng, radio, deporte)
                : establecimientoRepository.findActivosPorDeporte(deporte);
```
to:
```java
        List<Establecimiento> candidatos = conUbicacion
                ? establecimientoRepository.findCercanosYPorDeporte(lat, lng, radio, deporte)
                : establecimientoRepository.findActivosPorDeporte(deporte, PageRequest.of(0, MAX_CANDIDATOS_SIN_UBICACION));
```

In `ComplejoPublicoServiceTest.java`, add `import org.springframework.data.domain.Pageable;` (needed for the `any(Pageable.class)` matcher below). Every existing mock of `findActivosPorDeporte` now needs a second argument matcher, since the real call now always passes one. By this point in the plan there are more than a dozen occurrences accumulated across the original tests plus Tasks 1 and 4's additions — do not rely on a specific count. Search the file for every occurrence of `establecimientoRepository.findActivosPorDeporte(` and update each one; grep the file after your edit to confirm zero remain in the old single-argument form:

- `when(establecimientoRepository.findActivosPorDeporte(null))` → `when(establecimientoRepository.findActivosPorDeporte(isNull(), any(Pageable.class)))`
- `when(establecimientoRepository.findActivosPorDeporte(Deporte.PADEL))` → `when(establecimientoRepository.findActivosPorDeporte(eq(Deporte.PADEL), any(Pageable.class)))`

(Every other occurrence in the file passes `null` as the first argument, so it takes the `isNull()` form above. `isNull`, `eq`, and `any` are already statically imported in this file.)

- [ ] **Step 6: Run to see the service tests pass**

Run: `./mvnw test -Dtest=ComplejoPublicoServiceTest`
Expected: PASS (all tests — this step doesn't add new test cases, it fixes existing mocks to match the new method signature).

- [ ] **Step 7: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepository.java src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/EstablecimientoRepositoryTest.java
git commit -m "feat: acotar a 500 filas el listado publico sin ubicacion"
```

---

## Task 6: Final full-suite pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full suite one more time from a clean state**

Run: `./mvnw test`
Expected: BUILD SUCCESS, zero failures, zero errors.

- [ ] **Step 2: Sanity-check the spec's goals against what was built**

- [ ] `precioDesde`/`senaDesde` fall back to `precioBase` when a cancha has no tarifas → Task 1.
- [ ] The fecha/hora search filter excludes closed days, días no laborables, and blocked canchas → Task 4, backed by Tasks 2-3.
- [ ] `HorarioAtencionCalculator` extraction didn't change `DisponibilidadService`'s observable behavior → Task 2, Step 6.
- [ ] The no-location listing query is capped → Task 5.
- [ ] `./mvnw test` passes → this task.

- [ ] **Step 3: Report status to the user**

No commit for this task — it's verification-only.
