# Listado paginado de ventas de buffet — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/buffet/ventas`, a paginated listing of individual buffet sales for one establishment and date range, for OWNER/ADMIN.

**Architecture:** A new `buscarPaginado` query on `VentaRepository` (no `JOIN FETCH`, since the response has no item breakdown) backs a new `listarVentas` method on `VentaMetricasService` (the existing home for establishment-scoped read/reporting queries), exposed through a new `VentaResumenResponse` record and mapped by a new `VentaMapper.mapToResumenResponse`. A new `@GetMapping` on `VentaBuffetController` wires it up with the same `@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")` as `/metricas`.

**Tech Stack:** Spring Boot, Spring Data JPA, Spring Security (`@PreAuthorize`, JWT), JUnit 5 + Mockito, `@DataJpaTest` (H2), `@SpringBootTest` + `MockMvc` (H2).

**Repo:** `c:\Users\USER\Desktop\sacaladelangulo`, branch `test`, package root `com.matiasmeira.sacaladelangulo`.

## Global Constraints

- Sin cambios de esquema. Sin migraciones.
- No modificar el comportamiento de `/metricas` ni de los endpoints existentes (`POST`, `PUT /{id}/cancelar`).
- DTOs como `record`.
- Autorización: `OWNER`, `ADMIN` únicamente (mismo `@PreAuthorize` que `/metricas`).
- Sin `estado` en la query → incluye `CONFIRMADA` y `CANCELADA`. Con `estado` → filtra.
- `desde`/`hasta` son `LocalDate` `ISO.DATE`, ambos inclusive, requeridos.
- `page`/`size`/`sort` vía `Pageable`, default `size=20`, `sort=fechaHora,DESC`.
- No usar `JOIN FETCH` de una colección combinado con `Pageable` (Hibernate paginaría en memoria — warning HHH90003004). La respuesta no lleva `detalles`, así que no hace falta ese fetch join en absoluto.
- Un OWNER de otro establecimiento no puede leer estas ventas — mismo criterio (`AutorizacionEmpleadoService.validarPropietarioOAdmin` → `AccessDeniedException` → 403) que ya usa `/metricas`.
- Tests siguiendo el patrón del repo: `@DataJpaTest` para queries de repositorio, Mockito para service, `@SpringBootTest` + `MockMvc` + JWT real (no mocks de seguridad) para el endpoint end-to-end.

---

### Task 1: `VentaRepository.buscarPaginado`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepositoryTest.java` (create)

**Interfaces:**
- Produces: `VentaRepository.buscarPaginado(Long establecimientoId, EstadoVenta estado, LocalDateTime desde, LocalDateTime hasta, Pageable pageable) : Page<Venta>` — `estado` may be `null` (no filter).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepositoryTest.java`:

```java
package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("VentaRepository - buscarPaginado")
class VentaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private VentaRepository ventaRepository;

    private Establecimiento establecimiento;

    private Establecimiento persistirEstablecimiento(String slug) {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-" + slug + "@test.com")
                .password("hash")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        return entityManager.persist(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private Venta persistirVenta(EstadoVenta estado, LocalDateTime fechaHora, BigDecimal total) {
        return entityManager.persist(Venta.builder()
                .establecimiento(establecimiento)
                .fechaHora(fechaHora)
                .total(total)
                .estado(estado)
                .metodoPago(MetodoPago.EFECTIVO)
                .build());
    }

    @Test
    @DisplayName("buscarPaginado_SinEstado_TraeConfirmadaYCanceladaDentroDelRangoExcluyeFueraDeRango")
    void buscarPaginado_SinEstado_TraeConfirmadaYCanceladaDentroDelRangoExcluyeFueraDeRango() {
        establecimiento = persistirEstablecimiento("sin-estado");
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        persistirVenta(EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 2, 1, 10, 0), BigDecimal.valueOf(3000));
        entityManager.flush();
        entityManager.clear();

        Page<Venta> page = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().anyMatch(v -> v.getEstado() == EstadoVenta.CONFIRMADA));
        assertTrue(page.getContent().stream().anyMatch(v -> v.getEstado() == EstadoVenta.CANCELADA));
    }

    @Test
    @DisplayName("buscarPaginado_ConEstado_FiltraSoloEseEstado")
    void buscarPaginado_ConEstado_FiltraSoloEseEstado() {
        establecimiento = persistirEstablecimiento("con-estado");
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        persistirVenta(EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        entityManager.flush();
        entityManager.clear();

        Page<Venta> page = ventaRepository.buscarPaginado(
                establecimiento.getId(), EstadoVenta.CANCELADA,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(EstadoVenta.CANCELADA, page.getContent().get(0).getEstado());
    }

    @Test
    @DisplayName("buscarPaginado_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos")
    void buscarPaginado_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos() {
        establecimiento = persistirEstablecimiento("paginado");
        for (int i = 1; i <= 5; i++) {
            persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, i, 10, 0), BigDecimal.valueOf(1000L * i));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Venta> primeraPagina = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 2));

        assertEquals(5, primeraPagina.getTotalElements());
        assertEquals(3, primeraPagina.getTotalPages());
        assertEquals(2, primeraPagina.getContent().size());

        Page<Venta> ultimaPagina = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(2, 2));

        assertEquals(1, ultimaPagina.getContent().size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=VentaRepositoryTest`
Expected: FAIL to compile — `buscarPaginado` doesn't exist yet on `VentaRepository`.

- [ ] **Step 3: Implement `buscarPaginado`**

In `src/main/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepository.java`, add two imports after the existing `org.springframework.data.repository.query.Param` import:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

Then add this method inside the interface, after `findByIdConDetalles` and before the closing `}`:

```java
    /**
     * Listado paginado de ventas para GET /api/v1/buffet/ventas. A diferencia de
     * findByEstablecimientoIdAndEstadoAndFechaHoraBetween, estado es opcional (sin
     * filtro trae CONFIRMADA y CANCELADA) y no hace JOIN FETCH de detalles: la
     * respuesta de ese endpoint es un resumen sin el desglose de ítems, así que no
     * hace falta traerlos y se evita la trampa de combinar fetch join de una
     * colección con Pageable (Hibernate paginaría en memoria, warning HHH90003004).
     */
    @Query("SELECT v FROM Venta v WHERE v.establecimiento.id = :establecimientoId " +
            "AND (:estado IS NULL OR v.estado = :estado) " +
            "AND v.fechaHora BETWEEN :desde AND :hasta")
    Page<Venta> buscarPaginado(@Param("establecimientoId") Long establecimientoId,
                                @Param("estado") EstadoVenta estado,
                                @Param("desde") LocalDateTime desde,
                                @Param("hasta") LocalDateTime hasta,
                                Pageable pageable);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=VentaRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepository.java src/test/java/com/matiasmeira/sacaladelangulo/buffet/repository/VentaRepositoryTest.java
git commit -m "feat: add VentaRepository.buscarPaginado for buffet sales listing"
```

---

### Task 2: `VentaResumenResponse` + `VentaMapper.mapToResumenResponse` + `VentaMetricasService.listarVentas`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaResumenResponse.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaMapper.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasService.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasServiceTest.java` (modify)

**Interfaces:**
- Consumes: `VentaRepository.buscarPaginado(...)` from Task 1.
- Produces: `VentaResumenResponse(Long id, LocalDateTime fechaHora, BigDecimal total, String estado, String metodoPago, Long reservaId)`; `VentaMapper.mapToResumenResponse(Venta venta) : VentaResumenResponse`; `VentaMetricasService.listarVentas(Long establecimientoId, LocalDate desde, LocalDate hasta, EstadoVenta estado, String email, Pageable pageable) : Page<VentaResumenResponse>` — consumed by Task 3's controller.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasServiceTest.java`, add these imports (alongside the existing ones):

```java
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResumenResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
```

and add `import static org.mockito.ArgumentMatchers.isNull;` next to the other static `ArgumentMatchers` import.

Add a new `@Mock` field next to the existing ones (after `autorizacionEmpleadoService`, before `@InjectMocks`):

```java
    @Mock
    private VentaMapper ventaMapper;
```

Add these test methods inside the class, before the final closing `}`:

```java
    @Test
    @DisplayName("listarVentas_Exito_PasaFiltrosAlRepositorioYMapea")
    void listarVentas_Exito_PasaFiltrosAlRepositorioYMapea() {
        Venta venta = Venta.builder()
                .id(400L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.of(2026, 1, 10, 12, 0))
                .total(BigDecimal.valueOf(1500))
                .estado(EstadoVenta.CONFIRMADA)
                .metodoPago(MetodoPago.EFECTIVO)
                .build();

        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);
        Pageable pageable = PageRequest.of(0, 20);
        VentaResumenResponse resumen = new VentaResumenResponse(
                400L, venta.getFechaHora(), venta.getTotal(), "CONFIRMADA", "EFECTIVO", null);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(ventaRepository.buscarPaginado(eq(establecimiento.getId()), eq(EstadoVenta.CONFIRMADA),
                eq(desde.atStartOfDay()), eq(hasta.atTime(LocalTime.MAX)), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(venta)));
        when(ventaMapper.mapToResumenResponse(eq(venta))).thenReturn(resumen);

        Page<VentaResumenResponse> page = ventaMetricasService.listarVentas(
                establecimiento.getId(), desde, hasta, EstadoVenta.CONFIRMADA, dueno.getEmail(), pageable);

        assertEquals(1, page.getTotalElements());
        assertEquals(resumen, page.getContent().get(0));
    }

    @Test
    @DisplayName("listarVentas_SinEstado_PasaNullAlRepositorio")
    void listarVentas_SinEstado_PasaNullAlRepositorio() {
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);
        Pageable pageable = PageRequest.of(0, 20);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(ventaRepository.buscarPaginado(eq(establecimiento.getId()), isNull(),
                eq(desde.atStartOfDay()), eq(hasta.atTime(LocalTime.MAX)), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of()));

        ventaMetricasService.listarVentas(establecimiento.getId(), desde, hasta, null, dueno.getEmail(), pageable);

        verify(ventaRepository).buscarPaginado(eq(establecimiento.getId()), isNull(),
                eq(desde.atStartOfDay()), eq(hasta.atTime(LocalTime.MAX)), eq(pageable));
    }

    @Test
    @DisplayName("listarVentas_Fallo_DesdeMayorQueHasta")
    void listarVentas_Fallo_DesdeMayorQueHasta() {
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        assertThrows(IllegalArgumentException.class, () -> ventaMetricasService.listarVentas(
                establecimiento.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1),
                null, dueno.getEmail(), PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("listarVentas_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void listarVentas_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .rol(Role.OWNER)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> ventaMetricasService.listarVentas(
                establecimiento.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                null, otroDueno.getEmail(), PageRequest.of(0, 20)));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=VentaMetricasServiceTest`
Expected: FAIL to compile — `VentaResumenResponse` doesn't exist and `VentaMetricasService.listarVentas` doesn't exist.

- [ ] **Step 3: Create `VentaResumenResponse`**

Create `src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaResumenResponse.java`:

```java
package com.matiasmeira.sacaladelangulo.buffet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VentaResumenResponse(
        Long id,
        LocalDateTime fechaHora,
        BigDecimal total,
        String estado,
        String metodoPago,
        Long reservaId
) {
}
```

- [ ] **Step 4: Add `VentaMapper.mapToResumenResponse`**

In `src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaMapper.java`, add this method inside the class, after `mapToResponse` and before `mapDetalleToResponse`:

```java
    public VentaResumenResponse mapToResumenResponse(Venta venta) {
        return new VentaResumenResponse(
                venta.getId(),
                venta.getFechaHora(),
                venta.getTotal(),
                venta.getEstado().name(),
                venta.getMetodoPago().name(),
                venta.getReserva() != null ? venta.getReserva().getId() : null
        );
    }
```

- [ ] **Step 5: Add `VentaMetricasService.listarVentas`**

In `src/main/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasService.java`, add these imports:

```java
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResumenResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

Add a new field after `autorizacionEmpleadoService`:

```java
    private final VentaMapper ventaMapper;
```

Add this method after `obtenerMetricas` and before `calcularProductosMasVendidos`:

```java
    /**
     * Listado paginado de ventas de buffet de un establecimiento en un rango de
     * fechas (inclusive), para la tabla del front — sin desglose de ítems (ver
     * VentaResumenResponse). Mismo criterio de autorización y de rango de fechas
     * que obtenerMetricas.
     */
    @Transactional(readOnly = true)
    public Page<VentaResumenResponse> listarVentas(Long establecimientoId, LocalDate desde, LocalDate hasta,
                                                     EstadoVenta estado, String email, Pageable pageable) {
        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        if (desde.isAfter(hasta)) {
            throw new IllegalArgumentException("La fecha 'desde' no puede ser posterior a 'hasta'");
        }

        return ventaRepository.buscarPaginado(establecimientoId, estado, desde.atStartOfDay(), hasta.atTime(LocalTime.MAX), pageable)
                .map(ventaMapper::mapToResumenResponse);
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw test -Dtest=VentaMetricasServiceTest`
Expected: PASS (all tests, old and new).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaResumenResponse.java src/main/java/com/matiasmeira/sacaladelangulo/buffet/dto/VentaMapper.java src/main/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasService.java src/test/java/com/matiasmeira/sacaladelangulo/buffet/service/VentaMetricasServiceTest.java
git commit -m "feat: add VentaMetricasService.listarVentas with VentaResumenResponse"
```

---

### Task 3: `GET /api/v1/buffet/ventas` on `VentaBuffetController`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetController.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetControllerListarTest.java` (create)

**Interfaces:**
- Consumes: `VentaMetricasService.listarVentas(...)` from Task 2.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetControllerListarTest.java`:

```java
package com.matiasmeira.sacaladelangulo.buffet.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-venta-buffet-listar;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/buffet/ventas")
class VentaBuffetControllerListarTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private VentaRepository ventaRepository;

    @Autowired
    private JwtService jwtService;

    private Usuario crearDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    private Establecimiento crearEstablecimiento(String slug, Usuario dueno) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private void crearVenta(Establecimiento establecimiento, EstadoVenta estado, LocalDateTime fechaHora, BigDecimal total) {
        ventaRepository.save(Venta.builder()
                .establecimiento(establecimiento)
                .fechaHora(fechaHora)
                .total(total)
                .estado(estado)
                .metodoPago(MetodoPago.EFECTIVO)
                .build());
    }

    @Test
    @DisplayName("dueno_SinEstado_ListaConfirmadaYCanceladaOrdenadasPorFechaHoraDesc")
    void dueno_SinEstado_ListaConfirmadaYCanceladaOrdenadasPorFechaHoraDesc() throws Exception {
        Usuario dueno = crearDueno("dueno-listar1@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-1", dueno);
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        crearVenta(establecimiento, EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 2, 1, 10, 0), BigDecimal.valueOf(3000));

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].estado").value("CANCELADA"))
                .andExpect(jsonPath("$.content[1].estado").value("CONFIRMADA"));
    }

    @Test
    @DisplayName("dueno_ConEstadoCancelada_FiltraSoloCanceladas")
    void dueno_ConEstadoCancelada_FiltraSoloCanceladas() throws Exception {
        Usuario dueno = crearDueno("dueno-listar2@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-2", dueno);
        crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        crearVenta(establecimiento, EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .param("estado", "CANCELADA")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].estado").value("CANCELADA"));
    }

    @Test
    @DisplayName("dueno_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos")
    void dueno_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos() throws Exception {
        Usuario dueno = crearDueno("dueno-listar3@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-3", dueno);
        for (int i = 1; i <= 25; i++) {
            crearVenta(establecimiento, EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, i, 10, 0), BigDecimal.valueOf(100L * i));
        }

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(dueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(20));
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Devuelve403")
    void ownerDeOtroEstablecimiento_Devuelve403() throws Exception {
        Usuario dueno = crearDueno("dueno-listar4@test.com");
        Establecimiento establecimiento = crearEstablecimiento("listar-ventas-4", dueno);

        Usuario otroDueno = crearDueno("otro-dueno-listar4@test.com");
        crearEstablecimiento("otro-establecimiento-listar4", otroDueno);

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(otroDueno));

        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", establecimiento.getId().toString())
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/buffet/ventas")
                        .param("establecimientoId", "1")
                        .param("desde", "2026-01-01")
                        .param("hasta", "2026-01-31"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=VentaBuffetControllerListarTest`
Expected: FAIL — `GET /api/v1/buffet/ventas` doesn't exist yet (404, or 401/403 mismatch depending on Spring Security's handling of an unmapped path — either way, not the 200/403/401 the assertions expect).

- [ ] **Step 3: Implement the controller endpoint**

In `src/main/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetController.java`, add these imports:

```java
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResumenResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
```

Add this method after `obtenerMetricas`, before the final closing `}` of the class:

```java
    /**
     * Listado paginado de ventas de buffet de un establecimiento en un rango de
     * fechas, para la tabla de ventas del front (sin desglose de ítems). Sin
     * estado, incluye CONFIRMADA y CANCELADA.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ResponseEntity<Page<VentaResumenResponse>> listarVentas(
            @RequestParam Long establecimientoId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) EstadoVenta estado,
            @AuthenticationPrincipal UserDetails userDetails,
            @ParameterObject @PageableDefault(size = 20, sort = "fechaHora", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ventaMetricasService.listarVentas(
                establecimientoId, desde, hasta, estado, userDetails.getUsername(), pageable));
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=VentaBuffetControllerListarTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the full non-testcontainers suite**

Run: `./mvnw test`
Expected: PASS, no regressions in `VentaMetricasServiceTest`, `VentaServiceTest`, or any other existing test — `/metricas`, `POST`, and `PUT /{id}/cancelar` are untouched.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetController.java src/test/java/com/matiasmeira/sacaladelangulo/buffet/controller/VentaBuffetControllerListarTest.java
git commit -m "feat: add GET /api/v1/buffet/ventas paginated listing endpoint"
```

---

## Self-Review Notes

- **Spec coverage:** establecimientoId/desde/hasta required (Task 3 controller signature), estado optional with no-filter → both states (Task 1 query + Task 3 test), page/size/sort default size=20 sort=fechaHora,DESC (Task 3 `@PageableDefault`), `Page<VentaResumenResponse>` response (Task 2 DTO + Task 3 controller), fetch-join trap avoided by not fetching `detalles` at all (Task 1 query has no `JOIN FETCH`), cross-establishment 403 (Task 2 unit test + Task 3 integration test), real pagination totals with >1 page (Task 1 + Task 3 25-row test), no schema changes (none of the tasks touch entities/migrations), `/metricas` and other endpoints untouched (Task 3 Step 5 full suite run), DTOs as records (`VentaResumenResponse`).
- **Type consistency:** `VentaMetricasService.listarVentas` signature `(Long, LocalDate, LocalDate, EstadoVenta, String, Pageable) : Page<VentaResumenResponse>` matches its Task 2 test calls and its Task 3 controller call exactly. `VentaRepository.buscarPaginado` signature matches between Task 1's test, Task 2's mock stubs, and Task 2's service implementation.
