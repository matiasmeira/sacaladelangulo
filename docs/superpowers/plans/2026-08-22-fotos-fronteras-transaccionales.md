# Fronteras transaccionales en FotoEstablecimientoService — Plan

> **For agentic workers:** este plan corrige UN hallazgo Important del review final de la feature de fotos. Es una tarea única, no una secuencia. Los pasos usan checkbox (`- [ ]`).

**Goal:** Que la llamada HTTP a ImageKit deje de ocurrir con una conexión de base de datos retenida.

**Origen:** hallazgo Important del review final de `docs/superpowers/plans/2026-08-21-fotos-establecimiento-imagekit.md`.

**Spec de la feature:** `docs/superpowers/specs/2026-08-21-fotos-establecimiento-imagekit-design.md`

## El problema

`FotoEstablecimientoService` es `@Transactional` a nivel de clase. `subir()` abre la transacción en
`buscarEstablecimiento()` y **retiene la conexión de Hikari durante toda la llamada HTTP sincrónica a
ImageKit**. `borrar()` tiene la misma forma.

`application-prod.properties:45` fija `maximum-pool-size=${DB_POOL_MAX_SIZE:5}` a propósito, con un
comentario explicando que es para un tier chico de Postgres administrado. `connection-timeout=30000`.

Cinco subidas concurrentes — o simplemente ImageKit lento — vacían el pool, y **cualquier** request que
toque la base (reservas, login, ventas de buffet) encola hasta 30s y falla por timeout. Una feature
periférica puede frenar la app entera.

El repo ya tiene convención para diferir I/O externo (`@TransactionalEventListener(AFTER_COMMIT)` +
`@Async`, ver `ReservaNotificacionListener:49-50`), pero es fire-and-forget: sirve para mails, no acá,
porque la subida necesita la respuesta de ImageKit de forma sincrónica para devolverla al cliente.

## Decisiones ya tomadas (no re-litigar)

- **Mecanismo: `TransactionTemplate`.** Spring no aplica `@Transactional` en self-invocation, así que
  anotar métodos privados no hace nada. Se elige `TransactionTemplate` sobre extraer un bean colaborador:
  deja las fronteras explícitas en el mismo método, al lado del comentario que explica por qué existen, y
  no agrega una clase cuya única razón de ser es sortear el proxy de Spring. Es un patrón nuevo para este
  repo (hoy solo hay `@Transactional` declarativo); se documenta en el javadoc de la clase.
- **El tope de 10 fotos se revalida en la fase 3.** Partir la transacción ensancha la ventana TOCTOU que
  el review ya había marcado como Minor. Se vuelve a chequear con la lista recargada.
- **Sólo cambian `subir()` y `borrar()`.** `listar()` y `reordenar()` no llaman a ImageKit y quedan como
  están, con su `@Transactional` de método.

## Diseño

`@Transactional` sale del nivel de clase. Se inyecta `TransactionTemplate`. `listar()` y `reordenar()`
conservan su anotación de método (`readOnly = true` en `listar`).

### `subir()` en tres fases

1. **Fase 1 — transacción corta de lectura.** Resuelve el establecimiento (404), valida
   propietario/admin (403), y valida el archivo (400: magic bytes, tamaño, tope). Devuelve un record
   chico con lo que hace falta después (el id del actor y nada más que sea una entidad JPA — todo lo que
   salga de acá queda detached).
2. **Fase 2 — SIN transacción abierta.** `imageKitService.subir(...)`. Acá es donde antes se retenía la
   conexión.
3. **Fase 3 — transacción corta de escritura.** En este orden exacto:
   1. **Registrar primero el hook de compensación.** Si algo de esta fase falla, el rollback dispara el
      borrado en ImageKit del archivo recién subido. Registrarlo antes que nada hace que ningún fallo
      posterior deje un huérfano.
   2. Recargar el establecimiento (el de la fase 1 está detached).
   3. **Revalidar el tope de fotos.** Si otra subida concurrente lo pasó, lanzar
      `IllegalArgumentException` → 400. El rollback dispara la compensación registrada en el paso 1 y el
      archivo se borra solo de ImageKit; no hay que borrarlo a mano.
   4. Agregar la foto, `save`, auditar.

### `borrar()` en tres fases

Mismo esquema. La fase 1 resuelve, autoriza y encuentra la foto por `fileId` (404 si no está). La fase 2
llama a `imageKitService.borrar(fileId)` fuera de transacción, conservando el `try/catch` que loguea y
sigue — **esa decisión no cambia**: un fallo de ImageKit no puede dejar al dueño con una foto que no
puede sacar. La fase 3 recarga, saca la foto de la lista, guarda y audita.

## Tests

El test decisivo no es de comportamiento sino de **frontera transaccional**: hay que probar que durante
la llamada a ImageKit no hay transacción activa.

- [ ] **Test: `subir` llama a ImageKit fuera de transacción.** Mockear `ImageKitService.subir` con un
  `Answer` que capture `TransactionSynchronizationManager.isActualTransactionActive()` en el momento de
  la invocación, y assertar que es `false`. Este test falla contra el código actual — verificarlo así
  antes de tocar el servicio, y reportar esa falla como evidencia.
- [ ] **Test: `borrar` llama a ImageKit fuera de transacción.** Mismo mecanismo.
- [ ] **Test: el tope revalidado en fase 3 devuelve 400 y borra de ImageKit.** Simular que entre la fase
  1 y la 3 el establecimiento llegó a 10 fotos; assertar `IllegalArgumentException` y
  `verify(imageKitService).borrar(fileIdRecienSubido)`.
- [ ] Los tests existentes de `FotoEstablecimientoServiceTest` (11) y
  `FotoEstablecimientoControllerIntegrationTest` (5) deben seguir verdes sin modificarlos. Si alguno
  necesita cambios, es señal de que el comportamiento observable cambió — parar y reportar.

## Verificación

- `./mvnw test` — suite normal, hoy en **611** verde. Debe quedar en 611 + los tests nuevos.
- `./mvnw test -Dsurefire.excludedGroups= -Dgroups=testcontainers` — hoy **11** verde. Necesita Docker.

## Restricciones

- **Nunca `git add -A` ni `git add .`**: `src/test/java/.../publico/controller/ComplejoPublicoControllerIntegrationTest.java`
  es trabajo del usuario, sin commitear y ajeno a esta feature.
- No cambiar el comportamiento observable de ninguno de los cuatro endpoints: mismos códigos de estado,
  mismos cuerpos de respuesta, misma auditoría.
- Commits en español, Conventional Commits, imperativo. Prohibidas las palabras de relleno: robusto,
  eficiente, optimizado, dinámico, comprehensive, mejorado, potente, flexible, escalable, sólido.

## Fuera de alcance

Los otros hallazgos que el review final dejó abiertos, y que siguen anotados en el ledger
`.superpowers/sdd/2026-08-21-fotos-establecimiento-imagekit/progress.md`:

- Test de Testcontainers para el borrado de una foto del medio con 3+ fotos (el mismo mecanismo de
  duplicado transitorio que el Critical ya corregido; la constraint diferible ya lo cubre, falta el test).
- Test de rollback del hook de compensación.
- Tests HTTP de `GET` y `DELETE`.
- Escape hatch admin para establecimientos con fotos legacy (`fileId` NULL), que hoy no pueden reordenar.
