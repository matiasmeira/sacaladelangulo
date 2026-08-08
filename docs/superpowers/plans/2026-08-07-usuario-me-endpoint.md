# GET /api/v1/usuarios/me Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `GET /api/v1/usuarios/me`, returning the authenticated user's profile (id, email, nombre, rol, planSuscripcion, verification flags), plus `establecimientoId`/`permisos` when the user is an EMPLOYEE.

**Architecture:** New `PerfilResponse` record + `PerfilMapper` component in `auth/dto`, a new `UsuarioService.obtenerPerfil(String email)` method that loads the user by email (404 via `EntityNotFoundException` if missing) and delegates mapping, and a new `GET /me` handler on the existing `UsuarioController` using the same `@AuthenticationPrincipal UserDetails` pattern already used by `/telefono/solicitar-codigo` and `/telefono/verificar-codigo`. 401 for missing/invalid tokens needs no new code: `SecurityConfig`'s `anyRequest().authenticated()` already covers `/api/v1/usuarios/**`.

**Tech Stack:** Spring Boot, Spring Security (JWT), Spring Data JPA, Lombok, JUnit 5, Mockito, MockMvc, H2 (test).

## Global Constraints

- Follow existing patterns in `UsuarioController`/`UsuarioService`/`EmpleadoMapper` exactly — don't introduce new abstractions.
- DTOs are Java records (see `VerificarTokenResponse`, `EmpleadoResponse`).
- 404 for "user not found" uses `com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException` (already mapped to HTTP 404 in `GlobalExceptionHandler`).
- `planSuscripcion` is returned as-is from the entity, not gated by role (confirmed with user).
- `establecimientoId` and `permisos` are gated by role: populated only when `rol == Role.EMPLOYEE`; `null` / `Set.of()` otherwise.
- Design reference: `docs/superpowers/specs/2026-08-07-usuario-me-endpoint-design.md`.

---

### Task 1: PerfilResponse + PerfilMapper

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilResponse.java`
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapper.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapperTest.java`

**Interfaces:**
- Produces: `PerfilResponse(Long id, String email, String nombre, Role rol, PlanSuscripcion planSuscripcion, Boolean emailVerified, Boolean telefonoVerificado, Long establecimientoId, Set<PermisoEmpleado> permisos)` and `PerfilMapper.mapToResponse(Usuario usuario)` — consumed by Task 2's `UsuarioService.obtenerPerfil`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapperTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PerfilMapper")
class PerfilMapperTest {

    private final PerfilMapper perfilMapper = new PerfilMapper();

    @Test
    @DisplayName("mapToResponse_Empleado_IncluyeEstablecimientoIdYPermisos")
    void mapToResponse_Empleado_IncluyeEstablecimientoIdYPermisos() {
        Establecimiento establecimiento = Establecimiento.builder().id(7L).build();
        Usuario empleado = Usuario.builder()
                .id(1L)
                .email("empleado@test.com")
                .nombre("Juan")
                .rol(Role.EMPLOYEE)
                .emailVerified(true)
                .telefonoVerificado(false)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA))
                .build();

        PerfilResponse response = perfilMapper.mapToResponse(empleado);

        assertEquals(1L, response.id());
        assertEquals("empleado@test.com", response.email());
        assertEquals("Juan", response.nombre());
        assertEquals(Role.EMPLOYEE, response.rol());
        assertEquals(true, response.emailVerified());
        assertEquals(false, response.telefonoVerificado());
        assertEquals(7L, response.establecimientoId());
        assertEquals(Set.of(PermisoEmpleado.OPERAR_CAJA), response.permisos());
    }

    @Test
    @DisplayName("mapToResponse_NoEmpleado_EstablecimientoIdNuloYPermisosVacio")
    void mapToResponse_NoEmpleado_EstablecimientoIdNuloYPermisosVacio() {
        Usuario jugador = Usuario.builder()
                .id(2L)
                .email("jugador@test.com")
                .nombre("Ana")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build();

        PerfilResponse response = perfilMapper.mapToResponse(jugador);

        assertEquals(Role.PLAYER, response.rol());
        assertEquals(PlanSuscripcion.FREE, response.planSuscripcion());
        assertNull(response.establecimientoId());
        assertTrue(response.permisos().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PerfilMapperTest`
Expected: FAIL to compile — `PerfilMapper` and `PerfilResponse` don't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilResponse.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;

import java.util.Set;

/**
 * Perfil del usuario autenticado (GET /api/v1/usuarios/me): datos base más los flags
 * de verificación que el front usa para gatear el onboarding. establecimientoId y
 * permisos solo se completan para rol EMPLOYEE; para el resto van null/vacío.
 */
public record PerfilResponse(
        Long id,
        String email,
        String nombre,
        Role rol,
        PlanSuscripcion planSuscripcion,
        Boolean emailVerified,
        Boolean telefonoVerificado,
        Long establecimientoId,
        Set<PermisoEmpleado> permisos
) {
}
```

Create `src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapper.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.dto;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class PerfilMapper {

    public PerfilResponse mapToResponse(Usuario usuario) {
        boolean esEmpleado = usuario.getRol() == Role.EMPLOYEE;
        return new PerfilResponse(
                usuario.getId(),
                usuario.getEmail(),
                usuario.getNombre(),
                usuario.getRol(),
                usuario.getPlanSuscripcion(),
                usuario.getEmailVerified(),
                usuario.getTelefonoVerificado(),
                esEmpleado ? usuario.getEstablecimiento().getId() : null,
                esEmpleado ? usuario.getPermisos() : Set.of()
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PerfilMapperTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilResponse.java \
        src/main/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapper.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/dto/PerfilMapperTest.java
git commit -m "feat: add PerfilResponse/PerfilMapper for GET /me"
```

---

### Task 2: UsuarioService.obtenerPerfil

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioService.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioServiceTest.java`

**Interfaces:**
- Consumes: `PerfilMapper.mapToResponse(Usuario)` from Task 1.
- Produces: `UsuarioService.obtenerPerfil(String email): PerfilResponse` — consumed by Task 3's controller.

- [ ] **Step 1: Write the failing test**

Modify `src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioServiceTest.java` — add imports, a new `@Mock` field, and two new `@Test` methods:

```java
package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.dto.PerfilMapper;
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.CodigoVerificacionRepository;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UsuarioService - Verificación de teléfono por OTP")
class UsuarioServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private CodigoVerificacionRepository codigoVerificacionRepository;

    @Mock
    private PerfilMapper perfilMapper;

    @InjectMocks
    private UsuarioService usuarioService;

    @Test
    @DisplayName("solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio")
    void solicitarCodigo_Fallo_CarreraDeInsercion_TraduceAExcepcionDeNegocio() {
        when(codigoVerificacionRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> usuarioService.solicitarCodigo("jugador@test.com", "1122334455")
        );

        assertEquals("Ya se generó un código recientemente. Esperá unos segundos e intentá de nuevo.", exception.getMessage());
    }

    @Test
    @DisplayName("obtenerPerfil_Exito_DevuelvePerfilMapeado")
    void obtenerPerfil_Exito_DevuelvePerfilMapeado() {
        Usuario usuario = Usuario.builder().id(1L).email("jugador@test.com").rol(Role.PLAYER).build();
        PerfilResponse perfilEsperado = new PerfilResponse(
                1L, "jugador@test.com", null, Role.PLAYER, null, null, null, null, Set.of());
        when(usuarioRepository.findByEmail("jugador@test.com")).thenReturn(Optional.of(usuario));
        when(perfilMapper.mapToResponse(usuario)).thenReturn(perfilEsperado);

        PerfilResponse resultado = usuarioService.obtenerPerfil("jugador@test.com");

        assertEquals(perfilEsperado, resultado);
    }

    @Test
    @DisplayName("obtenerPerfil_UsuarioNoExiste_LanzaEntityNotFoundException")
    void obtenerPerfil_UsuarioNoExiste_LanzaEntityNotFoundException() {
        when(usuarioRepository.findByEmail("fantasma@test.com")).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> usuarioService.obtenerPerfil("fantasma@test.com"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=UsuarioServiceTest`
Expected: FAIL to compile — `UsuarioService` has no `obtenerPerfil` method and no `PerfilMapper` constructor dependency yet.

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioService.java`, add imports and the new field/method:

```java
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilMapper;
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
```

Add the field next to the existing ones (keeps `@RequiredArgsConstructor` wiring it automatically):

```java
    private final PerfilMapper perfilMapper;
```

Add the method:

```java
    /**
     * Perfil del usuario autenticado (GET /api/v1/usuarios/me).
     *
     * @param email Email del usuario autenticado
     */
    public PerfilResponse obtenerPerfil(String email) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"));
        return perfilMapper.mapToResponse(usuario);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=UsuarioServiceTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioService.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/service/UsuarioServiceTest.java
git commit -m "feat: add UsuarioService.obtenerPerfil"
```

---

### Task 3: GET /me endpoint + end-to-end test

**Files:**
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerMeTest.java`

**Interfaces:**
- Consumes: `UsuarioService.obtenerPerfil(String email): PerfilResponse` from Task 2.

This task's test is end-to-end (real `MockMvc` + real JWT via `JwtService` + real security filter chain, H2 in-memory DB) because "each role gets the right profile" and "no token → 401" are security-chain-level behaviors — mocking any of that away wouldn't actually verify them. It follows the same `@TestPropertySource` H2 setup already used by `RutasProtegidasCoincidenConControllersTest`, with its own DB name (`testdb-usuario-me`) so it doesn't share state with that test's cached Spring context.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerMeTest.java`:

```java
package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
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

import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-me;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/usuarios/me")
class UsuarioControllerMeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("admin_ObtieneSuPerfil_SinEstablecimientoNiPermisos")
    void admin_ObtieneSuPerfil_SinEstablecimientoNiPermisos() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin@me-test.com")
                .password("hash")
                .nombre("Admin Test")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(is(admin.getId()), Long.class))
                .andExpect(jsonPath("$.email").value("admin@me-test.com"))
                .andExpect(jsonPath("$.nombre").value("Admin Test"))
                .andExpect(jsonPath("$.rol").value("ADMIN"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.telefonoVerificado").value(false))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("owner_ObtieneSuPerfil_ConPlanSuscripcion")
    void owner_ObtieneSuPerfil_ConPlanSuscripcion() throws Exception {
        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner@me-test.com")
                .password("hash")
                .nombre("Owner Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.planSuscripcion").value("PREMIUM"))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("player_ObtieneSuPerfil_SinEstablecimientoNiPermisos")
    void player_ObtieneSuPerfil_SinEstablecimientoNiPermisos() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("player@me-test.com")
                .password("hash")
                .nombre("Player Test")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(jugador)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("PLAYER"))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("empleado_ObtieneSuPerfilConEstablecimientoIdYPermisos")
    void empleado_ObtieneSuPerfilConEstablecimientoIdYPermisos() throws Exception {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno@me-test.com")
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Cancha Test")
                .direccion("Calle Falsa 123")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)
                .build());

        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@me-test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA, PermisoEmpleado.CANCELAR_RESERVA))
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(empleado)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("EMPLOYEE"))
                .andExpect(jsonPath("$.establecimientoId").value(is(establecimiento.getId()), Long.class))
                .andExpect(jsonPath("$.permisos", containsInAnyOrder("OPERAR_CAJA", "CANCELAR_RESERVA")));
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/me"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=UsuarioControllerMeTest`
Expected: FAIL — `sinToken_Devuelve401` passes (already covered by `SecurityConfig`), but the role tests fail with 404 (no `GET /me` route exists yet on `UsuarioController`).

- [ ] **Step 3: Write minimal implementation**

In `src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java`, add the import and method:

```java
import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
```

```java
    @GetMapping("/me")
    public ResponseEntity<PerfilResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        return ResponseEntity.ok(usuarioService.obtenerPerfil(email));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=UsuarioControllerMeTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: PASS (no regressions — in particular `RutasProtegidasCoincidenConControllersTest` and `UsuarioServiceTest` still green)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioController.java \
        src/test/java/com/matiasmeira/sacaladelangulo/auth/controller/UsuarioControllerMeTest.java
git commit -m "feat: add GET /api/v1/usuarios/me endpoint"
```
