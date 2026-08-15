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
  `EmpleadoService`), hard delete.

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
    String detalle;               // nullable, 500 chars (mismo largo que RegistroAuditoria.detalle);
                                   // ej. "Forzado: 2 establecimiento(s) activo(s) sin desactivar"
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
   antes de eliminar la cuenta"), mapeada a 400. En self-delete no hay forma de saltear
   esto. En admin-delete, el admin puede pasar `forzar=true` (ver Endpoints) para
   moderación (ej. dueño problemático) — el guardrail se saltea pero **sin cascadear**: los
   establecimientos activos siguen activos y funcionando, ahora bajo una fila `Usuario`
   anonimizada como dueño. Cuando se usa `forzar`, `AuditoriaEliminacionUsuario.detalle`
   registra cuántos establecimientos activos tenía para que quede trazable.
6. Captura `email` y `nombre` reales en variables locales (para el mail de confirmación,
   antes de que la anonimización los pise).
7. Cancela las reservas del jugador en estado `CONFIRMADA`/`PENDIENTE_SENA`: lógica propia
   (no vía `cancelarReserva()`, ver Decisiones) — por cada una, `estado=CANCELADA`, guarda,
   publica `ReservaCanceladaEvent(reservaId, actorId=jugador.getId())`. Sin filtro de fecha
   adicional: en este dominio esos dos estados ya implican "todavía no jugada" (lo pasado
   transiciona a `FINALIZADA`/`AUSENTE`), así que filtrar solo por estado alcanza para
   cubrir "reservas futuras". Se usa el id del propio jugador como actor (no el del admin,
   ni siquiera en el flujo admin) para que `ReservaNotificacionListener` tome la rama
   `esElJugador`: el dueño recibe el mail de "se liberó una cancha" (lo relevante — sigue
   saliendo siempre). El mail al jugador ("cancelaste tu reserva") **no sale** — ver el
   guardrail de bounces más abajo.
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
- `DELETE /api/v1/admin/usuarios/{id}?forzar=true` — nuevo `AdminUsuarioController`
  (paquete `auth`), mismo patrón que `AdminMailsController`: sin `@PreAuthorize`, rol ADMIN
  validado a mano (`AccessDeniedException` si no es ADMIN → 403 ya mapeado). Si el target
  es `EMPLOYEE`, rechaza con 400 ("Los empleados se gestionan desde el establecimiento").
  `forzar` (query param `boolean`, default `false`) saltea el guardrail de OWNER con
  establecimientos activos — no existe en el endpoint self, solo el admin puede usarlo.
  `204 No Content`.

### Cerrar las puertas (Fase 5)

Varias de estas vías ya cierran solas por cómo interactúan `isActive`, el cambio de email
y `tokenVersion` — pero en Login y Recuperación de contraseña, donde el cierre dependía
solo de un efecto colateral, se agrega un chequeo explícito de `deletedAt`. Mismo criterio
en ambos: no confiar en que `isActive` siga significando "eliminado" para siempre (ya está
sobrecargado hoy, ver Hallazgos), ni en que el email placeholder alcance por sí solo.

- **Login** — chequeo explícito agregado: `UsuarioUserDetailsMapper.map()` pasa a calcular
  `enabled = Boolean.TRUE.equals(usuario.getIsActive()) && usuario.getDeletedAt() == null`.
  Hoy el bloqueo ya funciona solo con `isActive=false`, pero es el camino más crítico de
  los tres — si algún flujo futuro reactivara `isActive` sin tocar `deletedAt` (ej. un
  "reactivar cuenta" mal implementado), esto evita revivir el login de una cuenta ya
  anonimizada. Barato y queda testeable con un caso dedicado.
- **Recuperación de contraseña** — dos cambios: (1) `RecuperacionPasswordService.
  solicitarRecuperacion` agrega un chequeo explícito de `deletedAt`/`isActive` sobre el
  usuario encontrado por email (en vez de depender solo de que el email ya no matchee tras
  la anonimización), mismo criterio que en Login. (2) `resetPassword`: si el token es
  válido pero el usuario resuelto ya tiene `deletedAt != null` (caso borde: se pidió
  recuperación antes de eliminar la cuenta y el token stale se usa después), se trata igual
  que un token inválido (`TokenInvalidoException`) en vez de completar el cambio de
  contraseña — evita persistir el intento y, en particular, evita que el listener de
  confirmación (`PasswordCambiadaEvent`) intente mandar el mail de "tu contraseña cambió" al
  placeholder `@saque.deleted`.
- **Re-verificación de email/teléfono**: los endpoints operan sobre el usuario autenticado
  vía JWT; `tokenVersion+1` invalida cualquier token existente y el login ya está
  bloqueado, así que nunca se llega a esos endpoints. Sin cambios.
- **Búsquedas/listados** (`ClienteService`, `BloqueoJugadorService`): siguen mostrando la
  fila, ya anonimizada ("Usuario eliminado"). Decisión explícita: se prioriza preservar el
  historial del dueño sobre ocultar por completo al jugador dado de baja. Sin cambios.

### Evitar bounces contra cuentas eliminadas

`ReservaNotificacionListener` le manda mails al `jugador` de una reserva en dos métodos
(`enviarNotificacionesConfirmacion` y `enviarNotificacionesCancelacion`). Si ese jugador ya
fue anonimizado, su email es `deleted+{id}@saque.deleted` — un dominio que no existe. Un
bounce no es inofensivo: pega directo contra la reputación de envío del dominio en Resend,
justo cuando recién está arrancando. Se agrega un guard reusado en ambos métodos:

```java
private boolean puedeNotificar(Usuario usuario) {
    return usuario != null && usuario.getDeletedAt() == null && StringUtils.hasText(usuario.getEmail());
}
```

Reemplaza los `StringUtils.hasText(jugador.getEmail())` sueltos que ya existen. El mail al
**dueño** nunca pasa por este guard — su cuenta no está eliminada, y es el mail que importa
en el flujo de baja ("se liberó una cancha").

Alcance de este fix: solo `ReservaNotificacionListener`. El resto de los listeners de mail
del repo no necesitan el mismo guard: `bienvenida`/`verificacion` solo disparan para
cuentas nuevas o en onboarding (nunca eliminadas); el broadcast de marketing
(`OfertaMarketingBatchSender`) ya excluye por `acceptaMarketing=false`, que la anonimización
deja en `false`; `RecuperacionPasswordEmailListener` y la confirmación de cambio de
contraseña quedan cubiertos por los chequeos explícitos que se agregan arriba en
Recuperación de contraseña. Jobs batch de recordatorio de fin de prueba (`avisoFinPrueba*`)
quedan fuera de este spec — son un flujo separado, no disparado por esta feature.

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
  guardrail OWNER con/sin establecimientos activos, `forzar=true` salteando el guardrail
  (y dejando el/los establecimiento(s) intactos, sin cascadear), cancelación de reservas
  futuras CONFIRMADA/PENDIENTE_SENA, anonimización efectiva de PII, idempotencia (eliminar
  dos veces no rompe ni duplica efectos), rechazo de EMPLOYEE/ADMIN en self-delete, rechazo
  de EMPLOYEE en admin-delete, 403 de un no-ADMIN contra el endpoint admin.
- Integration test end-to-end (MockMvc + H2 + JWT real, mismo patrón que
  `UsuarioControllerMeTest`) para ambos endpoints, incluyendo `forzar=true`.
- Unit test de `ReservaNotificacionListener` (o equivalente) confirmando que
  `puedeNotificar` corta el envío al jugador cuando `deletedAt != null`, y que el mail al
  dueño sale igual.
- Test de `RecuperacionPasswordService`: `solicitarRecuperacion` sobre una cuenta eliminada
  no dispara el flujo (mismo comportamiento que "no existe"); `resetPassword` con un token
  válido pero usuario ya eliminado → `TokenInvalidoException`, sin enviar el mail de
  confirmación.
- Test de `UsuarioUserDetailsMapper`/login: un usuario con `isActive=true` pero
  `deletedAt != null` (estado que no debería ocurrir en producción, pero es justamente el
  caso que el chequeo explícito cubre) no puede loguear.
- `./mvnw test` al final.
