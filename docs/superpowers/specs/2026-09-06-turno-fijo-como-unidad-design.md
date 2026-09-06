# Turno fijo como unidad (ver, cancelar, renovar, editar cliente)

## Motivación

`POST /api/v1/reservas/semanal` crea N `Reserva` sueltas y no deja ninguna marca de que
pertenecen a la misma serie. El dueño carga "todos los martes 20hs para el Grupo del Colo"
en un click, pero a partir de ahí el turno fijo deja de existir como concepto: para darlo de
baja hay que cancelar hasta 52 reservas de a una desde la agenda, no hay forma de saber
mirando la grilla si un turno es parte de una serie, y renovarlo en enero significa recargar
día, hora, cancha y cliente a mano.

El adaptador del front ya documenta la limitación como conocida
(`saque-front/src/lib/api/adaptadores/agenda.ts`): *"`repiteSemanal` no existe y por eso
tampoco está en `Turno`: POST /reservas/semanal crea N reservas sueltas, sin marca de
pertenencia a la serie"*.

## Alcance

- Entidad `TurnoFijo` (la **regla**) + FK nullable `reservas.turno_fijo_id` (las
  **ocurrencias**) + migración V24.
- La creación pasa a `POST /api/v1/turnos-fijos` y persiste la regla junto con sus reservas.
- `ReservaResponse` expone `turnoFijoId`, para que la agenda pueda marcar el turno.
- Listado y detalle de turnos fijos del establecimiento.
- Cancelación de la serie completa o de una fecha en adelante, con resumen de omitidas.
- Renovación al año siguiente, reusando el mismo camino de creación (todo-o-nada).
- Edición de cliente/teléfono, sólo en series manuales.
- Front: página de turnos fijos, badge en la agenda y acceso a la serie desde el detalle.

**Fuera de alcance:**

- Cambiar día, hora o cancha de una serie existente. Se cancela y se carga otra. Decisión
  explícita del usuario: mover una serie es cancelar y recrear bajo lock, con fallo parcial
  posible, y no compra nada que dos clicks no resuelvan.
- Precio propio de la serie distinto de la tarifa de la cancha.
- Renovación automática por job. La renovación es un botón.
- Backfill de las series ya cargadas (ver Riesgos).
- Que el jugador vea o cancele su turno fijo como unidad. Sigue cancelando ocurrencias
  sueltas desde `PUT /reservas/{id}/cancelar`, con su `validarPlazoDeCancelacion`.

## Hallazgos de la exploración

- **Una columna de agrupación no alcanza.** Con un `serie_id` en `reservas` todo se deriva
  de las ocurrencias, y eso funciona para ver y cancelar — pero si el dueño cancela las 17
  ocurrencias, la serie deja de existir y no queda nada que renovar. La regla tiene que
  sobrevivir a sus reservas. De ahí la entidad.
- **Las ocurrencias siguen siendo `Reserva` materializadas.** Generarlas al vuelo desde la
  regla rompería el constraint de exclusión de Postgres (`V10__constraint_doble_booking.sql`),
  la grilla de disponibilidad y el lock pesimista de `bloquearCanchasRelacionadas`. La entidad
  describe la serie; las reservas siguen siendo la verdad de qué está ocupado.
- **Cancelar una serie repite el fan-out de eventos que ya se arregló al crearla.** Publicar
  un `ReservaCanceladaEvent` por ocurrencia encola una tarea `@Async` por fecha contra el pool
  de `AsyncConfig` (core 2, max 5, **cola 50**, `AbortPolicy`), verificado en
  `AsyncConfig.java:38-40`. Es exactamente el problema que documenta `TurnoFijoCreadoEvent`.
  Va un solo `TurnoFijoCanceladoEvent` con la lista de ids.
- **`IdempotencyFilter.RUTAS_PROTEGIDAS` hardcodea `/api/v1/reservas/semanal`**
  (`IdempotencyFilter.java:57-62`), y `RUTAS_CLAVE_OBLIGATORIA = RUTAS_PROTEGIDAS`. Mover la
  ruta sin actualizar ese set le saca en silencio la obligatoriedad de `Idempotency-Key`.
  **`RutasProtegidasCoincidenConControllersTest` lo detecta**: afirma que cada ruta hardcodeada
  resuelve a un `@PostMapping` real, así que la ruta vieja falla el test apenas se mueve. El
  guard existe y funciona; hay que actualizar el set en el mismo commit.
- **Migraciones**: la más alta es `V23__unico_empleado_activo_por_nombre.sql`. La siguiente
  libre es **V24**.
- **Los tests corren con `spring.flyway.enabled=false` y H2 `ddl-auto=create-drop`**
  (verificado en `RutasProtegidasCoincidenConControllersTest`). El esquema de test lo genera
  Hibernate: `./mvnw test` **no ejerce la V24**, igual que no ejerció la V23.
- **`AutorizacionEmpleadoService` ya tiene las tres puertas necesarias**: `validarPropietarioOAdmin`
  (escritura, la que usa hoy `crearReservaSemanal`), `validarLectura(est, email, permisos)` y
  la constante `PERMISOS_OPERATIVOS_DE_RESERVA` — el mismo conjunto con el que el empleado ya
  lee la agenda. No hace falta ningún permiso nuevo en `PermisoEmpleado`.
- **`Reserva` ya tiene `@Version`**, así que cancelar en lote está cubierto por optimistic
  locking. Cancelar no crea solapamiento: **no** necesita el lock pesimista de la cancha.
- **`AUSENTE` es terminal por diseño** y `cancelarReserva` lo protege explícitamente con un
  comentario que dice por qué (era el propio ausente quien podía borrar su no-show). La
  cancelación de la serie tiene que respetar la misma regla, no hacer un `UPDATE` masivo.

## Diseño

### Fase 1 — Modelo, creación linkeada y badge en la agenda

**Entidad `TurnoFijo`** (paquete `reserva.model`):

| campo | tipo | nota |
|---|---|---|
| `id` | `Long` | identity |
| `cancha` | `@ManyToOne Cancha` | not null |
| `deporteSeleccionado` | `Deporte` | not null |
| `diaSemana` | `DayOfWeek` | not null |
| `horaInicio` / `horaFin` | `LocalTime` | not null |
| `fechaInicioPeriodo` / `fechaFinPeriodo` | `LocalDate` | not null, inmutables |
| `jugador` | `@ManyToOne Usuario` | nullable |
| `nombreClienteManual` / `telefonoClienteManual` | `String` | nullable |
| `estado` | `EstadoTurnoFijo` | `ACTIVO` \| `CANCELADO` |
| `canceladoDesde` | `LocalDate` | nullable; no nulo sii `CANCELADO` |
| `renovadoDesdeId` | `Long` | nullable, único |
| `fechaCreacion` | `LocalDateTime` | `@PrePersist`, como `Reserva` |
| `version` | `Long` | `@Version` |

`Reserva` suma `@ManyToOne(fetch = LAZY) @JoinColumn(name = "turno_fijo_id") TurnoFijo turnoFijo`,
nullable. Todo lo que no es turno fijo queda en `NULL`.

**V24** crea la tabla, agrega la columna con FK (`ON DELETE RESTRICT` por defecto: nunca se
borra una serie, se cancela), un índice parcial `WHERE turno_fijo_id IS NOT NULL`, un índice
único parcial sobre `renovado_desde_id` (una serie se renueva **una** vez), y tres CHECK en la
línea de `V12__checks_montos.sql`:

```sql
CHECK (hora_inicio < hora_fin)
CHECK (fecha_inicio_periodo <= fecha_fin_periodo)
CHECK ((estado = 'ACTIVO'    AND cancelado_desde IS NULL)
    OR (estado = 'CANCELADO' AND cancelado_desde IS NOT NULL))
```

El XOR entre `jugador_id` y `nombre_cliente_manual` se documenta pero **no** se pone como
CHECK todavía: hay que verificarlo primero contra el servicio real, que hoy hace
`nombreClienteManual(jugador == null ? request.nombreClienteManual() : null)`. Un CHECK mal
puesto rompe inserts en producción; entra recién con un test que lo ejerza.

**Creación.** `crearReservaSemanal` se mueve a un `TurnoFijoService` y, dentro de la misma
transacción, persiste primero la regla y luego las ocurrencias con la FK seteada. El método
y la ruta viejos se **eliminan**, no quedan como alias: nada en producción los consume todavía,
y dos puertas a la misma operación es cómo una se queda sin el filtro de idempotencia. El resto
del método no cambia: mismas validaciones, mismo lock, mismo todo-o-nada, mismo
`TurnoFijoCreadoEvent`.

**Contrato.** `POST /api/v1/turnos-fijos` devuelve `TurnoFijoResponse` con las ocurrencias
adentro, en vez del `List<ReservaResponse>` pelado de hoy: quien llama creó una serie, no una
lista. Es un cambio de contrato deliberado, barato porque el único consumidor es
`saque-front/src/app/panel/agenda/page.tsx:232`, que hoy ignora el body.

`ReservaResponse` suma `turnoFijoId` (nullable). Con eso el front ya puede marcar el turno en
la grilla y borrar el comentario del adaptador que dice que es imposible.

**Idempotencia.** `RUTAS_PROTEGIDAS`: sale `/api/v1/reservas/semanal`, entra
`/api/v1/turnos-fijos`. `RUTAS_CLAVE_OBLIGATORIA` la sigue por ser el mismo set.

### Fase 2 — Listado, detalle y cancelación

- `GET /api/v1/turnos-fijos?establecimientoId=&estado=` → `Page<TurnoFijoResponse>`, cap de
  página 100 como el resto de `ReservaService`. `establecimientoId` es obligatorio (un dueño
  puede tener varios complejos); `estado` es opcional y por defecto trae **sólo `ACTIVO`**,
  que es lo que el dueño quiere ver — las canceladas se piden explícitamente. Lectura con `validarLectura(...,
  PERMISOS_OPERATIVOS_DE_RESERVA)`: el empleado que ya ve la agenda ve las series.
- `GET /api/v1/turnos-fijos/{id}` → la regla + sus ocurrencias.
- `POST /api/v1/turnos-fijos/{id}/cancelar`, body `{ desde?: LocalDate }`, OWNER/ADMIN.

**Qué se cancela.** Las ocurrencias con `fechaHoraInicio > max(ahora, desde.atStartOfDay())`.
Una sola expresión que hace lo correcto en los dos casos: cancelar "desde hoy" no toca el
turno de hoy a las 20 si ya son las 21 (ese hay que finalizarlo o marcarlo ausente, no
cancelarlo), y cancelar desde una fecha futura respeta esa fecha.

**Qué NO se cancela.** `FINALIZADA` (ya se jugó y se cobró), `AUSENTE` (es un registro de
no-show, y `cancelarReserva` ya protege ese caso a propósito), y las ya canceladas. Van al
resumen de la respuesta:

```json
{ "canceladas": 12, "omitidas": [{ "fecha": "...", "motivo": "FINALIZADA" }] }
```

Saltearlas en silencio es cómo el dueño termina creyendo que la serie está muerta mientras
sigue apareciendo en los reportes. La UI muestra el resumen.

La serie queda `estado = CANCELADO`, `canceladoDesde = desde`. Se publica **un**
`TurnoFijoCanceladoEvent(turnoFijoId, reservaIds)` → un mail al jugador con las fechas dadas
de baja y uno al dueño.

**El N+1 del listado.** `cantidadOcurrenciasActivas` y `proximaOcurrencia` no se calculan por
fila: se resuelven con **una** query agregada por los ids de la página
(`GROUP BY turno_fijo_id`). Es el tipo de campo que se vuelve N+1 sin que nadie lo note.

### Fase 3 — Renovación y edición de cliente

**`POST /api/v1/turnos-fijos/{id}/renovar`**, OWNER/ADMIN. Arma un pedido para el año
siguiente al de `fechaFinPeriodo` y lo pasa por **el mismo camino de creación** de la fase 1:
mismas validaciones, mismo lock, todo-o-nada. Crea una serie **nueva** con
`renovadoDesdeId = {id}`; no muta la vieja.

- Inicio = `max(1 de enero del año destino, hoy)`. El `max` con hoy es lo que hace que
  renovar tarde (en febrero, no en enero) no falle contra `@FutureOrPresent` ni contra
  `validarFechas`; `generarFechasDelPeriodo` ya busca la primera ocurrencia del día pedido a
  partir de ahí.
- Fin = 31 de diciembre del año destino.
- Renovar dos veces da 400 con un mensaje claro. (Decia 409 en la primera version de esta
  spec: al escribir el plan se verifico `GlobalExceptionHandler` y no existe ninguna
  excepcion de negocio que mapee a 409 — solo `DataIntegrityViolationException` y el fallo
  de optimistic locking. Todo error de negocio del dominio es `IllegalArgumentException` →
  400.) Sin el guard el segundo intento igual
  fallaría, pero por solapamiento — *"la cancha ya está reservada el 05/01"* — que no le dice
  nada al dueño. El índice único sobre `renovado_desde_id` lo garantiza a nivel base.

**`PATCH /api/v1/turnos-fijos/{id}/cliente`**, OWNER/ADMIN. Actualiza la regla y propaga a las
ocurrencias con `fechaHoraInicio > ahora` en estado `CONFIRMADA` o `PENDIENTE_SENA`. Las
pasadas no se tocan: son registro de lo que ocurrió. Si la serie está atada a un `jugadorId`
devuelve 400 — ahí el nombre sale de la cuenta del jugador, no de un campo editable.

### Front (`saque-front`)

- `src/lib/api/tipos/turnos-fijos.ts`, `endpoints/turnos-fijos.ts`, `hooks/api/use-turnos-fijos.ts`.
- `src/app/panel/turnos-fijos/page.tsx`: listado con día/hora/cancha/cliente/período y las
  acciones. Cancelar pide confirmación mostrando cuántos turnos futuros se van a dar de baja.
- Agenda: badge de serie en el timeline y "Parte de un turno fijo · Ver serie" en el detalle.
- `src/lib/panel/turno-fijo.ts` suma la aritmética nueva y testeable: `inicioDeRenovacion`
  (el `max` con hoy) y `ocurrenciasACancelar` (el `> max(ahora, desde)`). Son las dos reglas
  con bordes reales y las dos que el front necesita para previsualizar antes de confirmar.
- `agregarTurnoFijo` pasa a leer `TurnoFijoResponse`.

## Riesgos

- **Las series ya cargadas quedan sueltas** (`turno_fijo_id NULL`). Aparecen en la agenda como
  hasta hoy y no se pueden gestionar como unidad. Reconstruirlas por heurística (misma cancha,
  hora, cliente, cadencia semanal, `fecha_creacion` casi idéntica) puede fusionar dos series
  distintas o partir una: no se hace sin pedido explícito y sin mirar los datos reales.
- **La V24 no la ejerce ningún test** (Flyway apagado en tests, H2 vs Postgres). Se valida al
  levantar la app desde el IDE, igual que la V23 — que **sigue sin haberse ejecutado nunca**.
  Las dos se aplican juntas en el próximo arranque.
- **El cambio de contrato de `POST` rompe el front** hasta que se actualice. Van en la misma
  tanda; el `typecheck` lo agarra.
- **El CHECK del XOR cliente/jugador queda pendiente** a propósito, no olvidado.

## Tests

Backend, TDD, en `TurnoFijoServiceTest` salvo donde se indique:

1. Crear un turno fijo persiste la regla y linkea las N ocurrencias.
2. Cancelar la serie cancela sólo las futuras y deja intactas las pasadas.
3. Cancelar omite `FINALIZADA` y `AUSENTE`, y las reporta en el resumen.
4. Cancelar publica **un** evento, no N (`verify` sobre el publisher — el test que fija la
   lección de `TurnoFijoCreadoEvent`).
5. Cancelar desde una fecha futura no toca las anteriores a esa fecha.
6. Editar cliente propaga sólo a futuras; sobre una serie con jugador devuelve 400.
7. Renovar crea una serie del año siguiente con `renovadoDesdeId` seteado.
8. Renovar tarde (hoy > 1 de enero del año destino) arranca en hoy y no falla.
9. Renovar dos veces falla con mensaje propio.
10. Renovar es todo-o-nada si una sola fecha del año que viene choca.
11. Un empleado no puede cancelar, renovar ni editar (403); **sí** puede listar.
12. Un dueño de otro establecimiento no puede leer ni tocar la serie (403) — la auditoría
    adversarial del 2026-09-04 cerró con **cero hallazgos de IDOR** sobre 27 controllers; un
    controller nuevo es superficie nueva y no puede regresar eso.
13. `RutasProtegidasCoincidenConControllersTest` sigue verde tras mover la ruta (ya existe;
    acá sólo se verifica que se actualizó el set).

Front: los tests de `src/lib/panel/turno-fijo.ts` cubren `inicioDeRenovacion` y
`ocurrenciasACancelar`. Los componentes siguen sin cobertura — el repo no tiene
testing-library — y eso no cambia en esta tanda.
