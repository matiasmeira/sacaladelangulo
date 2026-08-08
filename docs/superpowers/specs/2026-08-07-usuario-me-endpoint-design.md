# GET /api/v1/usuarios/me — Perfil del usuario autenticado

## Motivación

El front necesita un endpoint para obtener el perfil del usuario autenticado (rol,
plan, flags de verificación de email/teléfono) para gatear el onboarding, y en el
caso de un empleado, su establecimiento y permisos.

## Alcance

Un único endpoint nuevo: `GET /api/v1/usuarios/me`, agregado a `UsuarioController`
(ya expone `/telefono/solicitar-codigo` y `/telefono/verificar-codigo` con el mismo
patrón de `@AuthenticationPrincipal UserDetails`).

## Diseño

**`auth/dto/PerfilResponse.java`** (record, mismo estilo que `EmpleadoResponse`):

```java
record PerfilResponse(
    Long id,
    String email,
    String nombre,
    Role rol,
    PlanSuscripcion planSuscripcion,
    Boolean emailVerified,
    Boolean telefonoVerificado,
    Long establecimientoId,
    Set<PermisoEmpleado> permisos
)
```

- `planSuscripcion`: se devuelve tal cual está en la entidad, sin forzar null por rol
  (decisión del usuario: no hay necesidad de tratarlo como campo gateado por rol).
- `establecimientoId` / `permisos`: gateados por rol. Si `rol == EMPLOYEE`,
  `establecimiento.getId()` y `permisos` de la entidad. Para el resto, `null` y
  `Set.of()` respectivamente.

**`auth/dto/PerfilMapper.java`**: `@Component`, mismo patrón que `EmpleadoMapper`
(empleado/dto). Un método `mapToResponse(Usuario usuario)` que arma el record de
arriba, con la lógica de gateo por rol.

**`UsuarioService.obtenerPerfil(String email)`**: `usuarioRepository.findByEmail(email)
.orElseThrow(() -> new EntityNotFoundException("Usuario no encontrado"))`, delega el
mapeo a `PerfilMapper`. Mismo patrón que `verificarCodigo` (que ya usa
`EntityNotFoundException` para el mismo caso).

**`UsuarioController`**: nuevo `@GetMapping("/me")`, misma forma que los otros dos
métodos del controller (extrae `email` de `userDetails.getUsername()`, delega al
service, devuelve `ResponseEntity.ok(...)`).

401 sin token no requiere código nuevo: `SecurityConfig` ya cubre `/api/v1/usuarios/**`
con la regla general `anyRequest().authenticated()`, manejada por
`RestAuthenticationEntryPoint`.

## Testing

El repo no tiene todavía ningún test HTTP end-to-end (MockMvc) — los tests existentes
son unitarios (Mockito) a nivel service/repository. "cada rol recibe su perfil
correcto" y "sin token → 401" son intrínsecamente comportamiento de la cadena de
seguridad real, no algo que un test con mocks pueda ejercitar honestamente.

Se agrega `UsuarioControllerMeTest`: `@SpringBootTest` + `@AutoConfigureMockMvc`, con
la misma config de H2 en memoria que ya usa `RutasProtegidasCoincidenConControllersTest`
(sin Flyway, `ddl-auto=create-drop`). Por cada test se persiste un `Usuario` real vía
`UsuarioRepository` y se firma un JWT real vía `JwtService` (no se mockea seguridad).

Casos:
- Un usuario por cada rol (ADMIN, OWNER, EMPLOYEE, PLAYER) recibe los campos correctos
  en el body de `GET /me`.
- El usuario EMPLOYEE ve su `establecimientoId` y sus `permisos`; los demás roles ven
  esos dos campos en `null`/vacío.
- `GET /me` sin header `Authorization` → 401.
