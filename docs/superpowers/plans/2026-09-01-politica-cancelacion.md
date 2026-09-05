# Política de cancelación configurable — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que el dueño de un establecimiento pueda consultar y actualizar `horasCancelacionAntesPartido`/`minutosGraciaCancelacion` (hoy fijos en los defaults 24h/30min, sin forma de cambiarlos desde la API), viendo cuántas reservas futuras quedan afectadas por el cambio.

**Architecture:** Sub-recurso propio bajo `/api/v1/establecimientos/{establecimientoId}/politicas-cancelacion` — mismo criterio que `FotoEstablecimientoController`/`CanchaService`: tiene DTOs, autorización y acción de auditoría propios, y no tiene nada que ver con el alta/edición del perfil del establecimiento que ya maneja `EstablecimientoService`. `PoliticaCancelacionService` reusa `AutorizacionEmpleadoService.validarPropietarioOAdmin` (mismo patrón que el resto del módulo) y `RegistroAuditoriaService.registrarSobreEstablecimiento` para el rastro de auditoría. El conteo de reservas afectadas se resuelve con un `@Query` nuevo en `ReservaRepository` (los dos campos de la política ya existen en `Establecimiento` desde el baseline y ya los consume `ReservaService.validarPlazoDeCancelacion` — no se toca esa lógica).

**Tech Stack:** Java 21, Spring Boot 3.5.14, Spring Data JPA, Lombok, JUnit 5 + Mockito (unit), MockMvc + H2 (integración), `springdoc`/`io.swagger.v3` para las anotaciones OpenAPI (ya en el classpath, usado hoy en `ReporteController`).

**Spec:** provista íntegra en el pedido original del usuario (política de cancelación configurable por establecimiento); no hay un archivo de spec separado en `docs/superpowers/specs/` para esta feature.

## Contexto y decisiones (no re-litigar)

- **Los campos ya existen y ya se usan.** `Establecimiento.horasCancelacionAntesPartido`/`minutosGraciaCancelacion` están en el baseline (`V1__baseline.sql`, columnas `INTEGER NOT NULL` sin default SQL) y ya los lee `ReservaService.validarPlazoDeCancelacion` (líneas 919-937). Lo único que falta es la forma de configurarlos. **No crear columnas ni tocar esa validación.**
- **Servicio nuevo, no extender `EstablecimientoService`.** El pedido daba libertad de elegir; `EstablecimientoService` tiene 241 líneas (no está "grande"), pero el patrón dominante y ya usado dos veces en este módulo (`CanchaService`, `FotoEstablecimientoService`) es: un sub-recurso con DTOs/audit/endpoint propio va en su propio servicio y controller, aunque opere sobre campos de `Establecimiento`. Mantiene `EstablecimientoService` enfocado en el perfil (nombre/dirección/horarios/servicios) y no mezcla una acción exclusiva de auditoría (`ACTUALIZAR_POLITICA_CANCELACION`) con las del perfil general.
- **`RegistroAuditoria` NO tiene columnas `valorAnterior`/`valorNuevo` estructuradas** (se verificó la entidad real: `id`, `empleado`, `actorId`, `establecimiento`, `accion`, `entidadAfectadaId`, `exitoso`, `detalle` (String, 500 chars), `fechaHora`). El "valor anterior y nuevo de ambos campos" que pide el spec va en el `String detalle`, mismo patrón que `CanchaService.actualizarCancha` (concatena a mano el precio nuevo en el detalle). No inventar campos que no existen.
- **Sin migración Flyway.** Próximo número libre: V22. No hace falta: la columna `accion` de `registro_auditoria_empleados` es `VARCHAR(255) NOT NULL` **sin** CHECK constraint (confirmado en `V1__baseline.sql`), y las dos columnas de política ya están en el esquema. Agregar un valor a `AccionAuditoria` no requiere tocar el esquema.
- **`AutorizacionEmpleadoService.validarPropietarioOAdmin` alcanza para el IDOR y para excluir empleados.** No valida nada especial para `EMPLOYEE` — cualquier usuario que no sea `ADMIN` ni el dueño real (`establecimiento.getDueno().getId()`) recibe `AccessDeniedException` (403). No hace falta un `PermisoEmpleado` nuevo ni lógica extra para el caso "empleado del propio establecimiento intenta configurar la política": ya cae en el mismo `else` que un dueño de otro establecimiento.
- **"Conteo de reservas futuras correcto, excluyendo pasadas y CANCELADA" se prueba a nivel repositorio (`@DataJpaTest`), no solo con mocks.** Un test de servicio con `ReservaRepository` mockeado no puede validar que el JPQL realmente filtre por estado y fecha — solo que el service haga el pasamanos. Por eso el plan agrega `ReservaRepositoryTest` (Task 1) contra H2 real, además de los tests de servicio (Task 2) que verifican que el resultado del repositorio llega intacto a la respuesta.
- **Sin anotaciones OpenAPI propias en el módulo establecimiento hoy** (ni `EstablecimientoController` ni `FotoEstablecimientoController` las tienen). El único precedente real en todo el repo es `ReporteController` (`@Tag`, `@Operation`, sin `@ApiResponse` en ningún lado). Este plan sigue ese estilo por ser el único documentado, aunque sea la primera vez dentro de `establecimiento.controller`.
- **Camino de PATH:** el pedido especifica `{establecimientoId}` como nombre de path variable (no `{id}`, que es lo que usan `EstablecimientoController`/`FotoEstablecimientoController`). Se respeta tal cual porque coincide además con la convención de `ReporteController` (`/establecimientos/{establecimientoId}/reportes`).

## Global Constraints

- **Repo:** `c:\Users\USER\Desktop\sacaladelangulo`. Rama actual: `test`.
- **Hay un cambio sin commitear que NO es de esta feature:** `.gitignore` modificado. **Nunca usar `git add -A` ni `git add .`** — stagear siempre por path explícito, y no tocar `.gitignore`.
- **Comandos:** `./mvnw test` corre toda la suite. `./mvnw test -Dtest=NombreDeLaClase` para una clase puntual.
- **Idioma:** identificadores en español (como el resto del repo: `obtener`, `actualizar`, `validarPropietarioOAdmin`), y todo mensaje de validación/error visible al usuario en español rioplatense con voseo ("Tenés que...", nunca "Debes...").
- **DTOs:** siempre `record`, nunca exponer entidades. Mensajes de `jakarta.validation` con `@Anotacion(value = X, message = "...")`, igual que `CanchaRequest`/`FeedbackRequest`.
- **Autorización:** solo `AutorizacionEmpleadoService.validarPropietarioOAdmin`. No crear ningún `PermisoEmpleado` nuevo — esto es configuración exclusiva del dueño/admin.
- **No modificar** `ReservaService.validarPlazoDeCancelacion` ni `cancelarReserva`.
- **Commits:** un commit por tarea, en español, Conventional Commits, modo imperativo, igual que el resto del historial (`feat(establecimientos): ...`).

---

### Task 1: `ReservaRepository.countReservasFuturasActivas`

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepositoryTest.java` (nuevo)

**Interfaces:**
- Consumes: nada (primera tarea).
- Produces: `ReservaRepository.countReservasFuturasActivas(Long estId, LocalDateTime ahora)` → `long`. Cuenta reservas con `estado IN (CONFIRMADA, PENDIENTE_SENA)` y `fechaHoraInicio > ahora` del establecimiento dado (vía `cancha.establecimiento.id`). La Task 2 lo consume desde `PoliticaCancelacionService`.

- [ ] **Step 1: Escribir el test de repositorio (falla al compilar: el método no existe)**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepositoryTest.java`:

```java
package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) que countReservasFuturasActivas cuente solo lo que
 * PoliticaCancelacionService necesita informar: CONFIRMADA/PENDIENTE_SENA con
 * fechaHoraInicio futura. Un test con el repositorio mockeado no ejercitaría el filtro de
 * estado ni el de fecha del JPQL.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("ReservaRepository - countReservasFuturasActivas")
class ReservaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ReservaRepository reservaRepository;

    @Test
    @DisplayName("countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas")
    void countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-politica@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Politica")
                .direccion("Calle Politica 123")
                .slug("complejo-politica")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(3)));
        // No deben contarse: cancelada futura y confirmada ya pasada.
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA, ahora.plusDays(1)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        entityManager.flush();

        long resultado = reservaRepository.countReservasFuturasActivas(establecimiento.getId(), ahora);

        assertEquals(2, resultado);
    }

    private Reserva reservaDe(Cancha cancha, EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .build();
    }
}
```

Run: `./mvnw test -Dtest=ReservaRepositoryTest`
Expected: FAIL — no compila (`cannot find symbol: countReservasFuturasActivas`).

- [ ] **Step 2: Agregar el método al repositorio**

En `src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java`, insertar justo antes del comentario `// ===== Reportes agregados (panel del dueño) =====` (línea 191 actual, después de `findByIdConEstablecimientoYDueno`):

```java
    /**
     * Cuenta las reservas futuras que todavía pueden verse afectadas por un cambio de
     * política de cancelación (CONFIRMADA o PENDIENTE_SENA, con fechaHoraInicio posterior
     * a "ahora"). Usado por PoliticaCancelacionService para informarle al dueño el impacto
     * de un cambio antes de persistirlo. No incluye CANCELADA/CANCELADA_PRERESERVA/
     * FINALIZADA/AUSENTE ni reservas ya jugadas.
     */
    @Query("SELECT COUNT(r) FROM Reserva r WHERE r.cancha.establecimiento.id = :estId " +
           "AND r.estado IN ('CONFIRMADA', 'PENDIENTE_SENA') AND r.fechaHoraInicio > :ahora")
    long countReservasFuturasActivas(@Param("estId") Long estId, @Param("ahora") LocalDateTime ahora);

```

No hacen falta imports nuevos: `@Query`, `@Param` y `LocalDateTime` ya están importados en el archivo.

- [ ] **Step 3: Correr el test y verificar que pasa**

Run: `./mvnw test -Dtest=ReservaRepositoryTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepositoryTest.java
git commit -m "feat(reserva): agrega countReservasFuturasActivas para medir impacto de cambios de politica"
```

---

### Task 2: DTOs, `AccionAuditoria` y `PoliticaCancelacionService`

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/PoliticaCancelacionResponse.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/ActualizarPoliticaCancelacionRequest.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/empleado/model/AccionAuditoria.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionService.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionServiceTest.java` (nuevo)

**Interfaces:**
- Consumes: `ReservaRepository.countReservasFuturasActivas(Long, LocalDateTime)` → `long` (Task 1). `EstablecimientoRepository.findById`/`save` (ya existen). `AutorizacionEmpleadoService.validarPropietarioOAdmin(Establecimiento, String)` → `Usuario` (ya existe). `RegistroAuditoriaService.registrarSobreEstablecimiento(Usuario, Establecimiento, AccionAuditoria, Long, String)` → `void` (ya existe).
- Produces:
  - `record PoliticaCancelacionResponse(Integer horasCancelacionAntesPartido, Integer minutosGraciaCancelacion, Integer reservasFuturasAfectadas)`
  - `record ActualizarPoliticaCancelacionRequest(Integer horasCancelacionAntesPartido, Integer minutosGraciaCancelacion)`
  - `PoliticaCancelacionService.obtenerPoliticaCancelacion(Long establecimientoId, String email)` → `PoliticaCancelacionResponse`
  - `PoliticaCancelacionService.actualizarPoliticaCancelacion(Long establecimientoId, ActualizarPoliticaCancelacionRequest request, String email)` → `PoliticaCancelacionResponse`
  - `AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION`

Ambos métodos y el nombre `PoliticaCancelacionService` los consume la Task 3 (`PoliticaCancelacionController`).

- [ ] **Step 1: Crear los DTOs**

`src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/PoliticaCancelacionResponse.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.dto;

/**
 * DTO de respuesta para la política de cancelación de un establecimiento.
 * reservasFuturasAfectadas solo viaja en la respuesta del PATCH (cuántas reservas
 * futuras quedan bajo la nueva política); en el GET siempre es null.
 */
public record PoliticaCancelacionResponse(
        Integer horasCancelacionAntesPartido,
        Integer minutosGraciaCancelacion,
        Integer reservasFuturasAfectadas
) {
}
```

`src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/ActualizarPoliticaCancelacionRequest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * DTO para actualizar la política de cancelación (semántica PATCH): un campo en null
 * significa "no modificar", por eso ninguno de los dos lleva @NotNull. Bean Validation no
 * evalúa @Min/@Max sobre un valor null, así que dejarlo en null pasa la validación sin
 * problema.
 */
public record ActualizarPoliticaCancelacionRequest(
        @Min(value = 0, message = "Las horas de cancelación no pueden ser negativas")
        @Max(value = 168, message = "Las horas de cancelación no pueden superar las 168 (una semana)")
        Integer horasCancelacionAntesPartido,

        @Min(value = 0, message = "Los minutos de gracia no pueden ser negativos")
        @Max(value = 1440, message = "Los minutos de gracia no pueden superar los 1440 (un día)")
        Integer minutosGraciaCancelacion
) {
}
```

- [ ] **Step 2: Agregar el valor de auditoría**

En `src/main/java/com/matiasmeira/sacaladelangulo/empleado/model/AccionAuditoria.java`, después de `ACTUALIZAR_CANCHA,` y antes del comentario `// Gestión de fotos del complejo...`:

```java
    ACTUALIZAR_CANCHA,

    // Configuración de la política de cancelación del establecimiento: acción exclusiva
    // del dueño/admin, mismo patrón que CREAR_CANCHA/ACTUALIZAR_CANCHA de arriba.
    ACTUALIZAR_POLITICA_CANCELACION,

```

(Reemplaza la línea `ACTUALIZAR_CANCHA,` existente por este bloque; el resto del enum no cambia.) No hace falta migración: `accion` es `VARCHAR(255)` sin CHECK constraint.

- [ ] **Step 3: Escribir el test del servicio (falla al compilar: `PoliticaCancelacionService` no existe)**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionServiceTest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PoliticaCancelacionService")
class PoliticaCancelacionServiceTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;
    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;
    @Mock
    private RegistroAuditoriaService registroAuditoriaService;
    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private PoliticaCancelacionService politicaCancelacionService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(1L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Complejo Test")
                .horasCancelacionAntesPartido(24)
                .minutosGraciaCancelacion(30)
                .dueno(dueno)
                .build();
    }

    @Test
    @DisplayName("obtenerPoliticaCancelacion_Exito_DevuelveValoresActualesConReservasFuturasAfectadasEnNull")
    void obtenerPoliticaCancelacion_Exito_DevuelveValoresActualesConReservasFuturasAfectadasEnNull() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        PoliticaCancelacionResponse response = politicaCancelacionService.obtenerPoliticaCancelacion(10L, dueno.getEmail());

        assertEquals(24, response.horasCancelacionAntesPartido());
        assertEquals(30, response.minutosGraciaCancelacion());
        assertNull(response.reservasFuturasAfectadas());
    }

    @Test
    @DisplayName("obtenerPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException")
    void obtenerPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> politicaCancelacionService.obtenerPoliticaCancelacion(99L, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_SoloHoras_ActualizaSoloEseCampo")
    void actualizarPoliticaCancelacion_SoloHoras_ActualizaSoloEseCampo() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(12, null);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(12, response.horasCancelacionAntesPartido());
        assertEquals(30, response.minutosGraciaCancelacion());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_SoloMinutos_ActualizaSoloEseCampo")
    void actualizarPoliticaCancelacion_SoloMinutos_ActualizaSoloEseCampo() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(null, 45);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(24, response.horasCancelacionAntesPartido());
        assertEquals(45, response.minutosGraciaCancelacion());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_AmbosCampos_ActualizaLosDosYDevuelveReservasAfectadas")
    void actualizarPoliticaCancelacion_AmbosCampos_ActualizaLosDosYDevuelveReservasAfectadas() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(3L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(6, 15);

        PoliticaCancelacionResponse response = politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        assertEquals(6, response.horasCancelacionAntesPartido());
        assertEquals(15, response.minutosGraciaCancelacion());
        assertEquals(3, response.reservasFuturasAfectadas());
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_AmbosNull_LanzaIllegalArgumentException")
    void actualizarPoliticaCancelacion_AmbosNull_LanzaIllegalArgumentException() {
        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(null, null);

        assertThrows(IllegalArgumentException.class,
                () -> politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException")
    void actualizarPoliticaCancelacion_EstablecimientoInexistente_LanzaEntityNotFoundException() {
        when(establecimientoRepository.findById(99L)).thenReturn(Optional.empty());
        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(12, null);

        assertThrows(EntityNotFoundException.class,
                () -> politicaCancelacionService.actualizarPoliticaCancelacion(99L, request, dueno.getEmail()));
    }

    @Test
    @DisplayName("actualizarPoliticaCancelacion_Exito_RegistraAuditoria")
    void actualizarPoliticaCancelacion_Exito_RegistraAuditoria() {
        when(establecimientoRepository.findById(10L)).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(establecimientoRepository.save(any(Establecimiento.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reservaRepository.countReservasFuturasActivas(eq(10L), any(LocalDateTime.class))).thenReturn(0L);

        ActualizarPoliticaCancelacionRequest request = new ActualizarPoliticaCancelacionRequest(6, 15);

        politicaCancelacionService.actualizarPoliticaCancelacion(10L, request, dueno.getEmail());

        verify(registroAuditoriaService).registrarSobreEstablecimiento(
                eq(dueno), eq(establecimiento), eq(AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION), eq(10L), any());
    }
}
```

Run: `./mvnw test -Dtest=PoliticaCancelacionServiceTest`
Expected: FAIL — no compila (`PoliticaCancelacionService` no existe).

- [ ] **Step 4: Implementar `PoliticaCancelacionService`**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionService.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Servicio de negocio para la política de cancelación de un establecimiento. Separado de
 * EstablecimientoService (que gestiona el alta/edición del perfil) porque es un sub-recurso
 * con endpoint, DTOs y acción de auditoría propios -- mismo criterio que
 * FotoEstablecimientoService/CanchaService.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PoliticaCancelacionService {

    private final EstablecimientoRepository establecimientoRepository;
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final RegistroAuditoriaService registroAuditoriaService;
    private final ReservaRepository reservaRepository;

    @Transactional(readOnly = true)
    public PoliticaCancelacionResponse obtenerPoliticaCancelacion(Long establecimientoId, String email) {
        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        return new PoliticaCancelacionResponse(
                establecimiento.getHorasCancelacionAntesPartido(),
                establecimiento.getMinutosGraciaCancelacion(),
                null
        );
    }

    public PoliticaCancelacionResponse actualizarPoliticaCancelacion(Long establecimientoId, ActualizarPoliticaCancelacionRequest request, String email) {
        if (request.horasCancelacionAntesPartido() == null && request.minutosGraciaCancelacion() == null) {
            throw new IllegalArgumentException("Tenés que indicar al menos un valor para actualizar la política de cancelación");
        }

        Establecimiento establecimiento = buscarEstablecimientoPorId(establecimientoId);
        Usuario usuarioAutenticado = autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, email);

        Integer horasAnteriores = establecimiento.getHorasCancelacionAntesPartido();
        Integer minutosAnteriores = establecimiento.getMinutosGraciaCancelacion();

        if (request.horasCancelacionAntesPartido() != null) {
            establecimiento.setHorasCancelacionAntesPartido(request.horasCancelacionAntesPartido());
        }
        if (request.minutosGraciaCancelacion() != null) {
            establecimiento.setMinutosGraciaCancelacion(request.minutosGraciaCancelacion());
        }

        Establecimiento establecimientoActualizado = establecimientoRepository.save(establecimiento);

        int reservasFuturasAfectadas = (int) reservaRepository.countReservasFuturasActivas(establecimientoId, LocalDateTime.now());

        registroAuditoriaService.registrarSobreEstablecimiento(usuarioAutenticado, establecimientoActualizado,
                AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION, establecimientoActualizado.getId(),
                String.format("Política de cancelación actualizada: horasCancelacionAntesPartido %d -> %d, minutosGraciaCancelacion %d -> %d",
                        horasAnteriores, establecimientoActualizado.getHorasCancelacionAntesPartido(),
                        minutosAnteriores, establecimientoActualizado.getMinutosGraciaCancelacion()));

        return new PoliticaCancelacionResponse(
                establecimientoActualizado.getHorasCancelacionAntesPartido(),
                establecimientoActualizado.getMinutosGraciaCancelacion(),
                reservasFuturasAfectadas
        );
    }

    private Establecimiento buscarEstablecimientoPorId(Long id) {
        return establecimientoRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));
    }
}
```

- [ ] **Step 5: Correr los tests y verificar que pasan**

Run: `./mvnw test -Dtest=PoliticaCancelacionServiceTest`
Expected: PASS, 8 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/PoliticaCancelacionResponse.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/dto/ActualizarPoliticaCancelacionRequest.java src/main/java/com/matiasmeira/sacaladelangulo/empleado/model/AccionAuditoria.java src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionService.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoliticaCancelacionServiceTest.java
git commit -m "feat(establecimientos): agrega PoliticaCancelacionService para configurar el plazo de cancelacion"
```

---

### Task 3: `PoliticaCancelacionController` y tests de integración

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionController.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionControllerIntegrationTest.java` (nuevo)

**Interfaces:**
- Consumes: `PoliticaCancelacionService.obtenerPoliticaCancelacion`/`actualizarPoliticaCancelacion` (Task 2), `ActualizarPoliticaCancelacionRequest`/`PoliticaCancelacionResponse` (Task 2).
- Produces: `GET /api/v1/establecimientos/{establecimientoId}/politicas-cancelacion`, `PATCH /api/v1/establecimientos/{establecimientoId}/politicas-cancelacion`. Última tarea del plan — nada más los consume.

- [ ] **Step 1: Escribir el test de integración (falla al compilar: el controller no existe)**

Crear `src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionControllerIntegrationTest.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-politica-cancelacion;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET/PATCH /api/v1/establecimientos/{id}/politicas-cancelacion")
class PoliticaCancelacionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    private Establecimiento sembrarLocal(String sufijo) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-" + sufijo + "@politica-test.com")
                .password("hash")
                .nombre("Dueno " + sufijo)
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + sufijo)
                .direccion("Calle 123")
                .slug("complejo-politica-" + sufijo)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .horasCancelacionAntesPartido(24)
                .minutosGraciaCancelacion(30)
                .dueno(dueno)
                .build());
    }

    private String duenoDe(Establecimiento establecimiento) {
        return establecimiento.getDueno().getEmail();
    }

    private String sembrarEmpleado(Establecimiento local, String sufijo) {
        String email = "empleado-" + sufijo + "@politica-test.interno";
        usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Empleado " + sufijo)
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(local)
                .build());
        return email;
    }

    @Test
    @DisplayName("dueno_ObtieneSuPolitica_200")
    void dueno_ObtieneSuPolitica_200() throws Exception {
        Establecimiento establecimiento = sembrarLocal("get-ok");

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(24))
                .andExpect(jsonPath("$.minutosGraciaCancelacion").value(30))
                .andExpect(jsonPath("$.reservasFuturasAfectadas").value(nullValue()));
    }

    @Test
    @DisplayName("dueno_ActualizaPolitica_200_PersisteYDevuelveReservasAfectadas")
    void dueno_ActualizaPolitica_200_PersisteYDevuelveReservasAfectadas() throws Exception {
        Establecimiento establecimiento = sembrarLocal("patch-ok");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(12))
                .andExpect(jsonPath("$.minutosGraciaCancelacion").value(30))
                .andExpect(jsonPath("$.reservasFuturasAfectadas").value(0));

        Establecimiento actualizado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(12, actualizado.getHorasCancelacionAntesPartido());
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Get_403")
    void ownerDeOtroEstablecimiento_Get_403() throws Exception {
        Establecimiento propio = sembrarLocal("idor-propio");
        Establecimiento ajeno = sembrarLocal("idor-ajeno");

        mockMvc.perform(get("/api/v1/establecimientos/" + ajeno.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(propio)).roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Patch_403")
    void ownerDeOtroEstablecimiento_Patch_403() throws Exception {
        Establecimiento propio = sembrarLocal("idor-patch-propio");
        Establecimiento ajeno = sembrarLocal("idor-patch-ajeno");

        mockMvc.perform(patch("/api/v1/establecimientos/" + ajeno.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(propio)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("empleadoDelPropioEstablecimiento_Patch_403")
    void empleadoDelPropioEstablecimiento_Patch_403() throws Exception {
        Establecimiento establecimiento = sembrarLocal("empleado-no");
        String emailEmpleado = sembrarEmpleado(establecimiento, "empleado-no");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(emailEmpleado).roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("horasNegativas_400")
    void horasNegativas_400() throws Exception {
        Establecimiento establecimiento = sembrarLocal("rango-negativo");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": -1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("horasPorEncimaDe168_400")
    void horasPorEncimaDe168_400() throws Exception {
        Establecimiento establecimiento = sembrarLocal("rango-alto");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 169}"))
                .andExpect(status().isBadRequest());
    }
}
```

Run: `./mvnw test -Dtest=PoliticaCancelacionControllerIntegrationTest`
Expected: FAIL — no compila (`PoliticaCancelacionController`/la ruta no existen, 404 en vez de los status esperados).

- [ ] **Step 2: Implementar `PoliticaCancelacionController`**

Crear `src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionController.java`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.establecimiento.dto.ActualizarPoliticaCancelacionRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.PoliticaCancelacionResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.service.PoliticaCancelacionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * Política de cancelación de un establecimiento. Sub-recurso propio, mismo criterio que
 * FotoEstablecimientoController: no son más métodos de EstablecimientoController porque no
 * tienen nada que ver con el alta/edición del perfil del establecimiento.
 *
 * @PreAuthorize filtra por rol; la validación de que ESTE establecimiento sea del usuario
 * la hace el servicio con validarPropietarioOAdmin.
 */
@RestController
@RequestMapping("/api/v1/establecimientos/{establecimientoId}/politicas-cancelacion")
@RequiredArgsConstructor
@Tag(name = "Política de cancelación", description = "Configuración del plazo mínimo y del período de gracia para que un jugador cancele su reserva")
public class PoliticaCancelacionController {

    private final PoliticaCancelacionService politicaCancelacionService;

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Consultar la política de cancelación",
            description = "Devuelve las horas mínimas de anticipación y los minutos de gracia configurados. reservasFuturasAfectadas siempre es null acá."
    )
    public ResponseEntity<PoliticaCancelacionResponse> obtener(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
                politicaCancelacionService.obtenerPoliticaCancelacion(establecimientoId, userDetails.getUsername()));
    }

    @PatchMapping
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @Operation(
            summary = "Actualizar la política de cancelación",
            description = "Actualiza horas de anticipación y/o minutos de gracia (semántica PATCH: un campo en null no se modifica). Devuelve cuántas reservas futuras quedan bajo la nueva política."
    )
    public ResponseEntity<PoliticaCancelacionResponse> actualizar(
            @PathVariable Long establecimientoId,
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody @Valid ActualizarPoliticaCancelacionRequest request) {
        return ResponseEntity.ok(
                politicaCancelacionService.actualizarPoliticaCancelacion(establecimientoId, request, userDetails.getUsername()));
    }
}
```

- [ ] **Step 3: Correr los tests y verificar que pasan**

Run: `./mvnw test -Dtest=PoliticaCancelacionControllerIntegrationTest`
Expected: PASS, 7 tests.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionController.java src/test/java/com/matiasmeira/sacaladelangulo/establecimiento/controller/PoliticaCancelacionControllerIntegrationTest.java
git commit -m "feat(establecimientos): expone GET/PATCH de politicas-cancelacion"
```

---

## Cierre

- [ ] **Correr la suite completa una última vez**

Run: `./mvnw test`
Expected: PASS, sin tests salteados que antes corrían.

- [ ] **Verificar que no quedó nada sin commitear de esta feature**

Run: `git status --short`
Expected: la única modificación pendiente debe ser `.gitignore`, que es trabajo previo del usuario y no se toca.
