# Eliminación de cuenta de usuario (soft-delete + anonimización)

## Motivación

Hoy no existe ninguna baja de cuenta. Se necesita un flujo de autoeliminación (PLAYER/OWNER)
y uno administrativo (ADMIN sobre cualquier cuenta) que respete la protección de datos
personales sin destruir historial transaccional: reservas, auditoría y pagos siguen
apuntando al usuario. Es soft-delete + anonimización de PII, no hard delete.

## Alcance

- Columna nueva `Usuario.deletedAt` + migración.
- Servicio central de anonimización con las reglas de negocio (guardrail de OWNER,
  cancelación de reservas futuras) antes de anonimizar.
- Dos endpoints: `DELETE /api/v1/usuarios/me` (self, PLAYER/OWNER) y
  `DELETE /api/v1/admin/usuarios/{id}` (ADMIN, cualquier cuenta salvo EMPLOYEE).
- Entidad de auditoría nueva y desacoplada del sistema existente (ver Hallazgos).
- Cierre de las vías de reingreso (login, recuperación de contraseña, re-verificación,
  búsquedas/listados) — la mayoría ya cierran solas, ver sección dedicada.
- Mail de confirmación de baja.
- Fuera de alcance: autoeliminación de EMPLOYEE (la gestiona el dueño vía
  `EmpleadoService`), override del guardrail de OWNER para ADMIN, hard delete.

## Hallazgos de la exploración (por qué el diseño no es un mapeo literal del pedido original)

- **`isActive` ya está sobrecargado**: en PLAYER significa "onboarding no completado"
  (`@PrePersist` lo defaultea a `false`); en EMPLOYEE lo reusa `desactivarEmpleado`. No es
  un flag de "eliminado" — por eso `deletedAt` es el discriminador real de una baja, e
  `isActive=false` es solo una consecuencia (y ya alcanza para bloquear el login sin
  cambios, porque `UsuarioUserDetailsMapper` lo mapea a `UserDetails.isEnabled()`).
- **`RegistroAuditoriaService` exige `Establecimiento` no-nulo** en sus 4 métodos de
  escritura — está pensado para acciones dueño/empleado dentro de un establecimiento. Una
  baja de PLAYER (o de OWNER sin establecimientos) no tiene ese contexto. Se decidió una
  entidad de auditoría nueva y mínima en vez de tocar esa tabla (ver Decisiones).
- **`cancelarReserva()` valida un plazo mínimo de cancelación** (24hs por defecto) cuando
  el actor cancela su propia reserva. La baja de cuenta necesita cancelar TODAS las
  reservas futuras activas del jugador sin importar ese plazo, así que no se reusa el
  método completo (ver Decisiones).
- **Migraciones**: el repo ya tiene hasta `V13__zona_publica_marketplace.sql` (el pedido
  original asumía que la última era V12). La siguiente libre es V14.
- **`ClienteService`/`BloqueoJugadorService`** leen la identidad del jugador a través de
  `Reserva.jugador`, sin filtrar por `isActive`. No hace falta tocarlos: al anonimizar el
  `Usuario`, lo que esas listas muestran ya queda scrubbeado ("Usuario eliminado").
- **No hay ningún uso de `passwordEncoder.matches(...)` en el repo hoy** — toda
  verificación de contraseña pasa por `AuthenticationManager`. La confirmación de
  contraseña en `DELETE /me` es el primer uso directo de `matches()`.

## Diseño

### Modelo y migraciones

`Usuario.deletedAt` (`LocalDateTime`, nullable). Una cuenta eliminada tiene `isActive=false`
Y `deletedAt != null`.

- `V14__usuario_deleted_at.sql`: `ALTER TABLE usuarios ADD COLUMN deleted_at TIMESTAMP NULL;`
- `V15__auditoria_eliminacion_usuario.sql`: crea la tabla de auditoría (ver abajo).

### Auditoría — entidad nueva

`auth/model/AuditoriaEliminacionUsuario.java` (nueva, no toca `RegistroAuditoria`):

```java
@Entity
@Table(name = "auditoria_eliminacion_usuario")
class AuditoriaEliminacionUsuario {
    Long id;
    @ManyToOne Usuario usuario;   // fila anonimizada; sigue existiendo, el FK es válido
    Long actorId;                 // null = autoeliminación; id del admin si fue admin
    @Enumerated(STRING) TipoEliminacionCuenta tipo; // AUTOELIMINACION, ELIMINACION_ADMIN
    LocalDateTime fechaHora;
}
```

`AuditoriaEliminacionUsuarioRepository extends JpaRepository<...>` — solo persistencia,
sin queries especiales.

### Servicio central — `UsuarioEliminacionService` (paquete `auth`)

Un único método `@Transactional` (uno para self, uno para admin, compartiendo la lógica
interna) que hace, en orden:

1. Resolver `Usuario` (por email en self-delete, por id en admin-delete vía
   `EntityNotFoundException` si no existe).
2. **Idempotencia**: si `usuario.getDeletedAt() != null`, retorna éxito sin repetir ningún
   efecto (no vuelve a cancelar reservas, no vuelve a auditar, no reenvía el mail).
3. Self-delete solamente: si `usuario.getRol()` es `EMPLOYEE` o `ADMIN`,
   `AccessDeniedException` ("Este endpoint no está disponible para tu rol") → 403. El
   pedido original solo habilita autoeliminación para PLAYER/OWNER; EMPLOYEE lo gestiona el
   dueño y ADMIN queda fuera de alcance. (El endpoint admin no tiene esta restricción más
   allá del rechazo de EMPLOYEE ya descripto — puede apuntar a PLAYER, OWNER o ADMIN.)
4. Self-delete: valida la contraseña del body con
   `passwordEncoder.matches(request.password(), usuario.getPassword())`; si no coincide,
   `BadCredentialsException` (ya mapeada a 401 en `GlobalExceptionHandler`).
5. Guardrail OWNER: `establecimientoRepository.findByDuenoIdAndIsActiveTrue(usuario.getId())`
   no vacío → `EstablecimientosActivosException` ("Desactivá o transferí tus complejos
   antes de eliminar la cuenta"), mapeada a 400. Se aplica igual en el flujo admin — no hay
   override, para no huerfanar establecimientos activos.
6. Captura `email` y `nombre` reales en variables locales (para el mail de confirmación,
   antes de que la anonimización los pise).
7. Cancela las reservas del jugador en estado `CONFIRMADA`/`PENDIENTE_SENA`: lógica propia
   (no vía `cancelarReserva()`, ver Decisiones) — por cada una, `estado=CANCELADA`, guarda,
   publica `ReservaCanceladaEvent(reservaId, actorId=jugador.getId())`. Sin filtro de fecha
   adicional: en este dominio esos dos estados ya implican "todavía no jugada" (lo pasado
   transiciona a `FINALIZADA`/`AUSENTE`), así que filtrar solo por estado alcanza para
   cubrir "reservas futuras". Se usa el id del propio jugador como actor (no el del admin,
   ni siquiera en el flujo admin) para que `ReservaNotificacionListener` tome la rama
   `esElJugador`: el dueño recibe el mail de "se liberó una cancha" (lo relevante) y el
   intento de mail al jugador simplemente rebota contra el placeholder (inofensivo, no
   rompe nada).
8. Anonimiza: `email → deleted+{id}@saque.deleted`, `nombre → "Usuario eliminado"`,
   `telefono → null`, `password → passwordEncoder.encode(UUID aleatorio)`,
   `acceptaMarketing → false`, `isActive → false`, `deletedAt → now()`,
   `tokenVersion += 1`. Preserva `id`, `rol`, `fechaCreacion` y todo lo demás.
9. Guarda el `Usuario`. Escribe `AuditoriaEliminacionUsuario` (tipo según self/admin,
   `actorId` null en self, id del admin en admin-delete).
10. Publica `CuentaEliminadaEvent(emailReal, nombreReal)` — valores copiados (strings
    planos), no referencia a la entidad ya anonimizada.

### Endpoints

- `DELETE /api/v1/usuarios/me` en `UsuarioController` (existente). Body
  `EliminarCuentaRequest(@NotBlank String password)`. Extrae el email del
  `@AuthenticationPrincipal`, delega a `usuarioEliminacionService.autoeliminar(email,
  password)`. `204 No Content`.
- `DELETE /api/v1/admin/usuarios/{id}` — nuevo `AdminUsuarioController` (paquete `auth`),
  mismo patrón que `AdminMailsController`: sin `@PreAuthorize`, rol ADMIN validado a mano
  (`AccessDeniedException` si no es ADMIN → 403 ya mapeado). Si el target es `EMPLOYEE`,
  rechaza con 400 ("Los empleados se gestionan desde el establecimiento"). `204 No
  Content`.

### Cerrar las puertas (Fase 5)

La mayor parte de esto ya funciona sin tocar código, por cómo interactúan `isActive`, el
cambio de email y `tokenVersion`:

- **Login**: `isActive=false` → `UsuarioUserDetailsMapper` devuelve `enabled=false` →
  Spring Security bloquea con `DisabledException`. Sin cambios.
- **Re-verificación de email/teléfono**: los endpoints operan sobre el usuario autenticado
  vía JWT; `tokenVersion+1` invalida cualquier token existente y el login ya está
  bloqueado, así que nunca se llega a esos endpoints. Sin cambios.
- **Búsquedas/listados** (`ClienteService`, `BloqueoJugadorService`): siguen mostrando la
  fila, ya anonimizada ("Usuario eliminado"). Decisión explícita: se prioriza preservar el
  historial del dueño sobre ocultar por completo al jugador dado de baja. Sin cambios.
- **Recuperación de contraseña** — único cambio real: `RecuperacionPasswordService.
  solicitarRecuperacion` busca por el email real, que ya no existe tras la anonimización
  (fue reemplazado por el placeholder), así que una solicitud post-eliminación no
  encuentra la fila. Se agrega igual un chequeo explícito de `deletedAt`/`isActive` ahí
  mismo, como defensa en profundidad y para que la intención quede explícita y testeable
  en vez de depender de un efecto colateral del renombrado de email.

### Email

- `CuentaEliminadaEvent(String email, String nombre)` — valores planos.
- `CuentaEliminadaEmailListener` en `auth/service`, mismo molde que
  `RecuperacionPasswordEmailListener` (`@Async @TransactionalEventListener(AFTER_COMMIT)`,
  sin refetch de entidad — no lo necesita).
- Template nuevo `templates/email/cuenta-eliminada.html`, mismo layout que el resto.

## Decisiones descartadas

- **Auditoría**: se consideró (a) hacer nullable `establecimiento` en `RegistroAuditoria` y
  sumar valores a `AccionAuditoria`, y (b) solo logging SLF4J sin tabla. Se descartaron: (a)
  arriesgaba romper reportes/consultas que asumen establecimiento presente en una tabla ya
  establecida; (b) no queda persistido ni consultable, y el pedido original pide
  explícitamente "registrá la baja en auditoría".
- **Cancelación de reservas**: se consideró reusar `cancelarReserva()` completo con un
  flag/overload para saltear `validarPlazoDeCancelacion`. Se descartó a favor de lógica
  independiente y mínima (estado + evento) porque evita tocar la firma de un método ya
  usado desde varios call sites por un caso de uso (baja de cuenta) que no necesita el
  resto de sus validaciones (rechazo de `FINALIZADA`, chequeos de autorización que no
  aplican acá porque el actor ya fue autorizado por el propio flujo de baja).
- **EMPLOYEE en el endpoint admin**: se consideró permitir que `DELETE
  /admin/usuarios/{id}` anonimice cualquier rol incluyendo EMPLOYEE. Se descartó para no
  duplicar el ciclo de vida que ya gestiona `EmpleadoService.desactivarEmpleado`.
- **Listados del dueño**: se consideró filtrar explícitamente `deletedAt != null` en
  `ClienteService`/`BloqueoJugadorService` para que el jugador desaparezca del todo. Se
  descartó porque el PII ya está scrubbeado y el pedido original prioriza preservar
  integridad referencial e historial.

## Testing

- Unit tests (Mockito) para `UsuarioEliminacionService`: contraseña correcta/incorrecta,
  guardrail OWNER con/sin establecimientos activos, cancelación de reservas futuras
  CONFIRMADA/PENDIENTE_SENA, anonimización efectiva de PII, idempotencia (eliminar dos
  veces no rompe ni duplica efectos), rechazo de EMPLOYEE/ADMIN en self-delete, rechazo de
  EMPLOYEE en admin-delete, 403 de un no-ADMIN contra el endpoint admin.
- Integration test end-to-end (MockMvc + H2 + JWT real, mismo patrón que
  `UsuarioControllerMeTest`) para ambos endpoints.
- Test de `RecuperacionPasswordService` confirmando que una cuenta eliminada no dispara el
  flujo de recuperación.
- `./mvnw test` al final.
