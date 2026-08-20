# Auditoría de persistencia, JPA y performance

**Alcance:** capa de datos del backend (Spring Boot 3.5, Java 21, JPA/Hibernate, Postgres).
**Contexto:** pre-launch, poca carga real. Se prioriza por impacto real con los datos creciendo, no por completitud.

**Estado: APLICADO.** Migraciones `V9`–`V12` en `src/main/resources/db/migration/`, más los cambios de código (A2, M1, mapeo de error). Verificado contra Postgres 16 real vía Testcontainers: Flyway aplica las 12 migraciones, `ddl-auto=validate` pasa (el esquema coincide con las entidades) y la suite completa queda en **416 tests, 0 failures, 0 errors**.

| Aplicado | Qué |
|---|---|
| `V9__indices_reservas.sql` | 4 índices del camino caliente (A1, A4) |
| `V10__constraint_doble_booking.sql` | `btree_gist` + `EXCLUDE USING gist` (A3) |
| `V11__indices_secundarios.sql` | 6 índices de buffet, gastos y caja (M2) |
| `V12__checks_montos.sql` | 7 `CHECK` espejando validación Java existente (M3) |
| `ReservaRepository` | `@EntityGraph` en 6 métodos de listado (A2) |
| `application.properties` | `spring.jpa.open-in-view=false` (M1) |
| `GlobalExceptionHandler` | `DataIntegrityViolationException` → 409, distinguiendo SQLSTATE `23P01` |
| `ReservaExclusionConstraintIntegrationTest` | 4 tests nuevos que prueban el constraint contra Postgres real |
| `GlobalExceptionHandlerTest` | 2 tests nuevos del mapeo de error |

---

## 1. Resumen

### El hallazgo #1 no es el que esperabas

El pedido anticipaba que lo más grave sería la ausencia del constraint de doble-booking. **No lo es.** El solapamiento ya está prevenido correctamente en la aplicación con un lock pesimista bien implementado (`SELECT ... FOR UPDATE` sobre la cancha y todas las canchas de su pool, con IDs ordenados ascendentemente para no generar deadlocks — [`CanchaRepository.lockPorIds`](src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/repository/CanchaRepository.java#L46-L48)). Los cuatro caminos de escritura que crean o mueven reservas lo toman, y la clase tiene `@Transactional` a nivel de clase, así que el lock se retiene hasta el commit. Funciona.

**El hallazgo #1 real es que la tabla `reservas` no tiene ningún índice más allá de la PK** — y eso interactúa de la peor forma posible con ese lock.

> **A1 — `reservas` sin índices, y el Seq Scan ocurre *dentro* del lock pesimista.**
> Postgres no indexa las columnas FK automáticamente. Hoy `cancha_id`, `jugador_id`, `fecha_hora_inicio` y `fecha_hora_fin` están sin indexar en la tabla que más crece del sistema. `findSuperpuestas()` se ejecuta **después** de tomar el `FOR UPDATE` y **antes** del commit: cuanto más tarda ese Seq Scan, más tiempo se retiene el lock, y más se serializan entre sí todas las reservas concurrentes del mismo establecimiento. El costo no crece linealmente con los datos — crece en la contención.

### Los tres siguientes más impactantes

| # | Hallazgo | Por qué importa |
|---|---|---|
| **A2** | N+1 al mapear listados de reservas | La agenda del panel dispara hasta ~100 queries extra por request |
| **A3** | Sin backstop de doble-booking en la base | El lock funciona, pero no hay red si un camino futuro se olvida de tomarlo |
| **A4** | `canchas.establecimiento_id` sin índice | Es el driver del join de *toda* consulta por establecimiento |

### Lo que ya está bien (y no hace falta tocar)

Vale decirlo explícitamente, porque buena parte de lo que el pedido pedía verificar **ya está resuelto** y no generó hallazgo:

- **Una sola caja `ABIERTO` por establecimiento: garantizada por índice único parcial** en la base, no solo por un check en Java — `uk_turno_caja_abierto_por_establecimiento ON turno_caja (establecimiento_id) WHERE estado = 'ABIERTO'` (V6). Exactamente lo que correspondía.
- **Cero relaciones `FetchType.EAGER`** en todo el proyecto.
- **Cero `findAll()` sin paginar.**
- **Cero `double`/`float` para dinero.** Todo `BigDecimal` mapeado a `NUMERIC(38,2)`.
- **`@Transactional(readOnly = true)`** presente en las lecturas y reportes.
- **Paginación real con `Pageable`** en todos los listados que crecen, y con tope duro de 100 (`capPageSize`) para que el cliente no pueda pedir la tabla entera.
- **Agregaciones resueltas en SQL** (`SUM`, `COUNT`, `GROUP BY`), no trayendo filas a memoria. Los reportes usan proyecciones livianas en lugar de entidades completas.
- **`JOIN FETCH` ya aplicado** donde más dolía: `findSuperpuestas` y `findByIdConEstablecimientoYDueno`.
- **`ddl-auto=validate`** en la config base y Flyway a cargo del esquema.

---

## 2. Hallazgos por prioridad

### 🔴 ALTO

---

#### A1 — `reservas` no tiene ningún índice más allá de la PK

**Ubicación:** [`V1__baseline.sql`](src/main/resources/db/migration/V1__baseline.sql) — tabla `reservas`.

**Qué pasa hoy.** La tabla declara dos FK (`jugador_id`, `cancha_id`) y ningún índice. Postgres **no** crea índices para columnas FK automáticamente (a diferencia de MySQL/InnoDB, que sí). En todo el esquema hay solo 3 `CREATE INDEX`, ninguno sobre `reservas`.

Toda consulta contra la tabla más caliente del sistema hace Seq Scan:

| Query | Cuándo corre |
|---|---|
| `findSuperpuestas` | En **cada** creación de reserva y **cada** render de la grilla de disponibilidad |
| `findReservasEnRangoDiario` | Agenda del panel (pantalla más usada) |
| `findCanchaIdsConSolapamiento` | Disponibilidad en lote |
| `liberarReservasVencidas` | Job programado, en loop, para siempre |
| Reportes (facturación, ocupación, horarios, clientes) | Panel del dueño |

**Impacto concreto con los datos creciendo.** Lo grave no es el Seq Scan aislado, es **dónde** ocurre. En [`ReservaService.crearReserva`](src/main/java/com/matiasmeira/sacaladelangulo/reserva/service/ReservaService.java#L137-L144) el orden es:

1. `bloquearCanchasRelacionadas(...)` → `SELECT ... FOR UPDATE` (lock tomado)
2. `findSuperpuestas(...)` → **Seq Scan de toda la tabla `reservas`**
3. validaciones + `save` + commit (lock liberado)

El lock se retiene durante todo el paso 2. Con 500 reservas el escaneo es imperceptible; con 200.000 (un año de varios establecimientos) pasa a decenas de milisegundos, y ese tiempo se multiplica por cada transacción que quiera reservar en el mismo establecimiento, porque están serializadas por el lock. Un pico de demanda (apertura de turnos del fin de semana) es exactamente el escenario donde se nota.

Además `liberarReservasVencidas` escanea la tabla completa en cada pasada del job, incluso cuando no hay nada para liberar.

**Fix.** [`P1__indices_reservas.sql`](migraciones-propuestas/P1__indices_reservas.sql). El índice clave es `(cancha_id, fecha_hora_inicio, fecha_hora_fin)`: igualdad primero, rango después. Más un índice **parcial** sobre `expira_en WHERE estado = 'PENDIENTE_SENA'` para el job, que se mantiene mínimo porque ese estado es transitorio.

**Cuello de botella real.** Es el ítem que haría primero.

---

#### A2 — N+1 al mapear cualquier listado de reservas a DTO

**Ubicación:** [`ReservaMapper.mapToResponse`](src/main/java/com/matiasmeira/sacaladelangulo/reserva/dto/ReservaMapper.java) + las queries de listado en [`ReservaRepository`](src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java).

**Qué pasa hoy.** `mapToResponse` dereferencia dos asociaciones `LAZY`:

```java
jugador.getNombre()              // Reserva.jugador es LAZY
reserva.getCancha().getNombre()  // Reserva.cancha es LAZY
```

`findSuperpuestas` sí tiene `JOIN FETCH r.cancha`. Pero **las queries que alimentan los listados paginados no tienen ningún fetch join**:

- `findReservasEnRangoDiario` / `...IncluyendoCanceladas`
- `findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn`
- `findByJugadorId` / `findByJugadorIdAndEstado`

Y los servicios hacen `reservas.map(reservaMapper::mapToResponse)`, que materializa inmediatamente.

**Impacto concreto.** El peor caso es `obtenerReservasPorEstablecimientoYFecha` (agenda del establecimiento, la pantalla más usada del panel): la página trae reservas de varias canchas y de jugadores distintos. Con el tope de página de 100 → **1 query del listado + ~100 queries de `jugador` + una por cancha distinta**. Los jugadores casi no se repiten dentro de una página, así que el caché de primer nivel no ayuda.

Nota de precisión: esto **no** produce `LazyInitializationException` — el `.map()` ocurre dentro del método `@Transactional(readOnly = true)`, así que la sesión está abierta. El problema es puramente de cantidad de round-trips.

**Fix.** `@EntityGraph` en las queries de listado, sin tocar lógica de negocio:

```java
@EntityGraph(attributePaths = {"jugador", "cancha"})
Page<Reserva> findReservasEnRangoDiario(...);
```

`@EntityGraph` sobre una query paginada es seguro acá porque ambas son asociaciones `@ManyToOne` (to-one): Hibernate las resuelve con un `LEFT JOIN` sin romper la paginación. **Esto no valdría** si se tratara de una colección `@OneToMany` — ahí Hibernate paginaría en memoria (`HHH000104`), que es el efecto contrario al buscado.

Detalle: `jugador` es nullable (reservas de mostrador), así que el `@EntityGraph` debe resolver a `LEFT JOIN` — es el comportamiento por defecto de `attributePaths`, pero conviene verificarlo con el SQL a la vista.

**Cuello de botella real.**

---

#### A3 — No hay backstop de doble-booking a nivel base

**Ubicación:** tabla `reservas`, sin constraint de exclusión.

**Qué pasa hoy.** Como se dijo en el resumen: el lock pesimista **sí** previene el solapamiento y está bien hecho. Lo que no hay es una red debajo. El `@Version` de `Reserva`, correctamente, no cumple ese rol — y el comentario de la propia entidad ya lo documenta con precisión.

**Impacto concreto.** Riesgo de corrupción silenciosa de datos si:
- un camino de escritura futuro se agrega sin tomar el lock (nada en el código lo obliga; es una convención sostenida a mano),
- se corrige data a mano contra la base,
- un import o script masivo escribe reservas sin pasar por el servicio.

Una reserva doble no se detecta hasta que dos equipos se presentan a la misma cancha a la misma hora. Es el tipo de bug que cuesta reputación, no CPU.

**Fix.** [`P2__constraint_doble_booking.sql`](migraciones-propuestas/P2__constraint_doble_booking.sql) — `btree_gist` + `EXCLUDE USING gist`. **Tiene tres salvedades que hay que leer antes de aplicar**, y una limitación que no se puede cerrar con un constraint. Ver sección 3.

**Defensa en profundidad, no bug abierto.** Lo etiqueto así a propósito: no hay un agujero explotable hoy. Es seguro de cara al futuro.

---

#### A4 — `canchas.establecimiento_id` sin índice

**Ubicación:** [`V1__baseline.sql`](src/main/resources/db/migration/V1__baseline.sql) — tabla `canchas`.

**Qué pasa hoy.** Misma causa que A1. `canchas` es una tabla chica, lo que hace tentador descartarlo — pero es el **punto de entrada** de casi todo:

- `findByEstablecimientoIdAndIsActiveTrue` se llama en **toda** creación de reserva (para armar el pool de canchas) y en cada grilla de disponibilidad.
- Es el lado que dirige el join de `findSuperpuestas` (`WHERE c.establecimiento.id = :estId`) y de todos los reportes por establecimiento.

**Impacto concreto.** Crece con la cantidad de establecimientos en la plataforma, no con las canchas de uno solo. Es multi-tenant: cada establecimiento nuevo hace más lento el camino caliente de todos los demás. Barato de arreglar, y se paga en cada request.

**Fix.** Incluido en [`P1`](migraciones-propuestas/P1__indices_reservas.sql).

**Cuello de botella real.**

---

### 🟡 MEDIO

---

#### M1 — `spring.jpa.open-in-view` en `true` (default implícito)

**Ubicación:** no está declarado en ningún `application*.properties` → Spring Boot usa `true`.

**Qué pasa hoy.** La sesión de Hibernate se mantiene abierta durante toda la request, incluida la serialización de la respuesta. Mantiene una conexión del pool tomada más tiempo del necesario — y el pool de producción está dimensionado en **5 conexiones** (`DB_POOL_MAX_SIZE:5`, decisión correcta para un tier chico de Postgres administrado, pero deja poco margen).

**Precisión honesta:** `open-in-view` **no** es lo que esconde el N+1 de A2. Ese N+1 ocurre dentro del `@Transactional`, y seguiría ocurriendo igual con la property en `false`. El valor de desactivarlo es otro: que cualquier acceso lazy que hoy se resuelva "de casualidad" en la capa web falle ruidosamente en desarrollo en vez de degradar en silencio.

**Impacto concreto.** Con 5 conexiones, retener cada una durante la serialización reduce el throughput efectivo antes de lo que sugiere el número de conexiones. Y el arranque de Boot ya emite el warning correspondiente.

**Fix.** `spring.jpa.open-in-view=false`. **Antes de aplicarlo**, arreglar A2 (los `@EntityGraph`), porque son justamente esas asociaciones lazy las que podrían quedar sin inicializar. Orden importa: **A2 primero, M1 después**, y probar los endpoints de listado y detalle de reserva.

---

#### M2 — FKs sin índice en tablas que crecen a diario

**Ubicación:** `ventas_buffet`, `detalles_venta`, `gastos`, `productos_buffet`.

**Qué pasa hoy.** Mismo patrón que A1, un escalón más abajo en impacto:

| Tabla | Columna sin índice | Consulta afectada |
|---|---|---|
| `gastos` | `(establecimiento_id, fecha)` | Listado paginado + reportes de gastos |
| `ventas_buffet` | `(establecimiento_id, fecha_hora)`, `reserva_id` | Reportes de facturación, consumo por turno |
| `detalles_venta` | `venta_id` | Abrir el detalle de una venta |
| `movimiento_caja` | `(origen, referencia_id)` | `findTopByOrigenAndReferenciaId...` en cada anulación que impacta caja |
| `productos_buffet` | `establecimiento_id` | Grilla de productos |

`movimiento_caja` **ya tiene** `idx_movimiento_caja_turno` (V6), que cubre el listado por turno — el hueco es solo la búsqueda por origen/referencia.

**Impacto concreto.** Una fila por venta / gasto / movimiento por día y por establecimiento. Crece de forma sostenida y sin techo natural. Ninguna de estas consultas corre dentro de un lock, por eso es MEDIO y no ALTO.

**Fix.** [`P3__indices_secundarios.sql`](migraciones-propuestas/P3__indices_secundarios.sql). El de `gastos` va **parcial** (`WHERE is_active = true`), porque la anulación es lógica (V7) y todas las consultas filtran por activo.

---

#### M3 — Invariantes de monto sin respaldo en el esquema

**Ubicación:** `reservas.precio_total`, `reservas.sena_pagada`, `gastos.monto`, `ventas_buffet.total`, `movimiento_caja.monto`, `productos_buffet.stock`/`precio`.

**Qué pasa hoy.** La nullability entre entidad y columna es **coherente** en todo lo que revisé (incluido `jugador_id` nullable, correcto para reservas de mostrador). Lo que falta son los `CHECK`: nada a nivel base impide un `precio_total` negativo, una `sena_pagada` mayor al total, o `stock < 0`.

**Impacto concreto.** Bajo hoy: la validación en Java cubre los caminos normales. El valor es el mismo que A3 — evitar que un dato corrupto entre por un camino no previsto y descuadre un arqueo de caja meses después, cuando ya es difícil rastrear el origen.

**Fix.** No lo escribí como migración a propósito: un `CHECK` mal calibrado rompe producción por un caso de negocio legítimo que no anticipé (¿un gasto puede ser negativo para representar un reintegro? ¿una nota de crédito?). Conviene decidir caso por caso con el negocio a la vista, no de una barrida. Candidatos seguros: `monto > 0` en `gastos` y `precio_total >= 0` / `sena_pagada >= 0` en `reservas`.

---

### 🟢 BAJO / gold-plating (descartar por ahora)

---

#### G1 — Sin batching de inserts en la reserva semanal — ❌ **gold-plating**

`crearReservaSemanal` hace `saveAll(reservasAGuardar)` sin `hibernate.jdbc.batch_size` configurado.

**Por qué lo descarto, y por qué la solución "obvia" no funcionaría:** `Reserva` usa `GenerationType.IDENTITY`. Con IDENTITY, Hibernate **no puede batchear inserts en absoluto** — necesita el ID generado inmediatamente después de cada fila, así que desactiva el batching aunque se configure `batch_size`. Configurarlo sería una no-op que además da falsa sensación de haberlo resuelto. Habilitarlo de verdad exige migrar a `SEQUENCE`, que es un cambio de esquema con riesgo real.

¿Vale la pena? Un turno fijo de 3 meses genera ~13 filas. Trece inserts en una operación que el dueño ejecuta ocasionalmente. **No.**

#### G2 — Agrupaciones de reportes resueltas en memoria — ❌ **gold-plating**

`findFechaYPrecioParaSerieTemporal` y `findFechasParaHorariosPedidos` traen proyecciones y agrupan en Java.

**Por qué lo descarto:** ya son proyecciones livianas (dos columnas, no entidades), están acotadas por rango de fecha, y los comentarios del repositorio documentan la razón técnica real — `EXTRACT(DOW ...)` no es portable en JPQL y el truncado de fecha tiene tipo de retorno inconsistente entre versiones de Hibernate. La decisión está tomada con criterio. Moverlo a SQL nativo agregaría acoplamiento a Postgres a cambio de nada medible en este volumen.

#### G3 — `b.getCancha().getId()` en `ReporteOcupacionService` — ❌ **no es un hallazgo**

Parece un N+1 pero no lo es: llamar `getId()` sobre un proxy lazy **no** dispara query, porque la FK ya está en el proxy. Lo anoto solo para que no se "arregle" por error en una revisión futura.

---

## 3. Migraciones aplicadas

En `src/main/resources/db/migration/`, aplicadas y verificadas contra Postgres 16 real.

| Archivo | Contenido |
|---|---|
| [`V9__indices_reservas.sql`](src/main/resources/db/migration/V9__indices_reservas.sql) | 4 índices: el compuesto de solapamiento, `canchas(establecimiento_id)`, "mis reservas", y el parcial del job de expiración |
| [`V10__constraint_doble_booking.sql`](src/main/resources/db/migration/V10__constraint_doble_booking.sql) | `btree_gist` + `EXCLUDE USING gist` |
| [`V11__indices_secundarios.sql`](src/main/resources/db/migration/V11__indices_secundarios.sql) | 6 índices de buffet, gastos y caja |
| [`V12__checks_montos.sql`](src/main/resources/db/migration/V12__checks_montos.sql) | 7 `CHECK`, cada uno espejando una validación Java ya existente |

### Sobre V10 — las decisiones de estados, que no son las obvias

El constraint propuesto es:

```sql
ALTER TABLE reservas
    ADD CONSTRAINT excl_reservas_solapadas
    EXCLUDE USING gist (
        cancha_id WITH =,
        tsrange(fecha_hora_inicio, fecha_hora_fin, '[)') WITH &&
    )
    WHERE (estado IN ('CONFIRMADA', 'FINALIZADA', 'AUSENTE'));
```

**Tres decisiones sobre los estados, dos de ellas distintas de lo que asumía el pedido:**

1. **`PENDIENTE_SENA` queda FUERA, y es obligatorio que quede fuera.** La app considera libre una pre-reserva cuya ventana venció (`expira_en < now()`), sin esperar al job — es la cláusula `(r.estado != 'PENDIENTE_SENA' OR r.expiraEn IS NULL OR r.expiraEn > :ahora)` de `findSuperpuestas`. Un constraint **no puede expresar "vencida"**: `now()` no es inmutable y Postgres lo rechaza en el predicado de un índice. Si incluyéramos `PENDIENTE_SENA`, una pre-reserva abandonada bloquearía el horario a nivel base hasta que el job la limpie, y **el rebooking legítimo que la app sí permite fallaría con un error de constraint**. Es exactamente el escenario que el pedido pedía evitar. Consecuencia asumida: las pre-reservas quedan protegidas solo por el lock pesimista, lo cual es aceptable (son transitorias, 10 minutos).

2. **`CANCELADA` y `CANCELADA_PRERESERVA` quedan fuera** — liberan el slot, igual que en `findSuperpuestas`. Acá sí coincide con lo esperado.

3. **`AUSENTE` queda DENTRO (sigue ocupando).** El pedido asumía que libera el slot; en este código **no lo hace** — `findSuperpuestas` solo descarta las dos canceladas. Un no-show se marca sobre un turno que ya pasó, así que no hay rebooking que habilitar. El constraint replica la definición real de la app.

> **Regla que guió las tres:** el constraint nunca debe ser más restrictivo que la aplicación, o rechaza escrituras que la app considera válidas.

**Limitación que un `EXCLUDE` no puede cerrar.** El modelo soporta canchas **lógicas** compuestas por canchas **físicas** (`Cancha.canchasFisicas` / `canchasNecesarias`, ver `PoolCanchaCalculator`). Reservar la cancha lógica "Cancha Grande" consume las físicas "Cancha 1" y "Cancha 2" — pero esas filas tienen **`cancha_id` distinto**. Un `EXCLUDE` sobre `cancha_id WITH =` solo detecta choques con el mismo `cancha_id`: **no detecta el choque lógica-vs-física**, que es el caso de doble-booking más sutil del sistema.

Es decir, P2 es un backstop **parcial**: cubre el caso simple y frecuente, y el caso de pool sigue dependiendo del lock de la aplicación. Cerrarlo en la base exigiría desnormalizar (ej. una tabla `ocupacion_cancha_fisica` con una fila por cancha física ocupada y el `EXCLUDE` sobre ella). Eso es un cambio de modelo, no un índice — **no lo propongo**. Si el negocio todavía no usa canchas compuestas, P2 alcanza y sobra.

**Verificación de las tres decisiones.** No quedaron como afirmación en un comentario: [`ReservaExclusionConstraintIntegrationTest`](src/test/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaExclusionConstraintIntegrationTest.java) las prueba contra Postgres real, escribiendo **directo contra el repositorio** para saltear la validación de `ReservaService` — que es justamente el escenario que el constraint viene a cubrir. Los 4 casos pasan:

| Caso | Resultado esperado y verificado |
|---|---|
| Dos `CONFIRMADA` con solapamiento parcial | La base **rechaza** la segunda |
| Turnos adyacentes (14–15 y 15–16) | **Ambos coexisten** (semántica `[)`) |
| `CANCELADA` + rebooking del mismo slot | **Permitido** |
| `PENDIENTE_SENA` vencida + rebooking | **Permitido** ← el caso que obligó a dejarla fuera |

Si alguien cambia esa cláusula `WHERE`, estos tests lo detectan.

**Manejo del error, ya implementado.** `GlobalExceptionHandler` mapea `DataIntegrityViolationException` a **409**, distinguiendo por SQLSTATE: `23P01` (exclusion_violation) devuelve el mismo mensaje de negocio que el conflicto de bloqueo optimista ("la cancha acaba de ser reservada…"); el resto de las violaciones (UNIQUE, FK, los CHECK de V12) devuelven un 409 genérico y se loguean con detalle, **sin filtrar el nombre del constraint al cliente** (misma filosofía que `server.error.include-message=never` en prod).

Detalle de implementación: el SQLSTATE no está en la excepción de más afuera — Spring envuelve la `SQLException` original y Hibernate la envuelve a su vez — así que hay que caminar la cadena de causas para encontrarlo.

---

## 4. N+1 encontrados (resuelto)

Solo hay **uno** real, pero está en la pantalla más usada del panel. Ya está corregido en [`ReservaRepository`](src/main/java/com/matiasmeira/sacaladelangulo/reserva/repository/ReservaRepository.java).

| Dónde | Asociación | Fix |
|---|---|---|
| `findReservasEnRangoDiario` | `jugador`, `cancha` | `@EntityGraph(attributePaths = {"jugador", "cancha"})` |
| `findReservasEnRangoDiarioIncluyendoCanceladas` | `jugador`, `cancha` | idem |
| `findByCancha_Establecimiento_IdAndFechaHoraInicioBetweenAndEstadoNotIn` | `jugador`, `cancha` | idem |
| `findByCancha_Establecimiento_IdAndFechaHoraInicioBetween` | `jugador`, `cancha` | idem |
| `findByJugadorId` / `findByJugadorIdAndEstado` | `cancha` | `@EntityGraph(attributePaths = {"cancha"})` |

Todas alimentan `ReservaMapper.mapToResponse`, que dereferencia ambas asociaciones lazy.

**Por qué `@EntityGraph` y no `JOIN FETCH`:** las cinco son consultas **paginadas**. Un `JOIN FETCH` sobre una query con `Pageable` obliga a Hibernate a paginar en memoria cuando hay colecciones. Acá ambas asociaciones son `@ManyToOne` (to-one), así que tanto `JOIN FETCH` como `@EntityGraph` son seguros — pero `@EntityGraph` es declarativo y no obliga a reescribir el JPQL de cada query, incluidas las derivadas por nombre de método, que ni siquiera tienen JPQL para tocar.

**Verificación indirecta ya hecha:** la suite pasa con `spring.jpa.open-in-view=false`. Sin el fetching completo, esas asociaciones habrían tirado `LazyInitializationException`.

**Verificación directa pendiente (recomendada):** activar `spring.jpa.show-sql=true` en el perfil `dev` (ya está), pegarle a la agenda con ~30 reservas de jugadores distintos y contar los `select`. Es la única forma de confirmar que el `LEFT JOIN` salió en una sola consulta y no en dos batches.

**Falsos positivos revisados y descartados:** los reportes (`reportes/service/`) usan proyecciones, no entidades — no tienen N+1. `findSuperpuestas` y `findByIdConEstablecimientoYDueno` ya traen sus fetch joins. `CanchaRepository` ya usa `@EntityGraph` para `canchasFisicas` y `deportes`.

---

## 5. Estado del plan

### Hecho

| | Qué | Verificación |
|---|---|---|
| ✅ | **V9 — índices de `reservas` y `canchas`** (A1, A4) | Flyway aplica; `ddl-auto=validate` pasa |
| ✅ | **A2 — `@EntityGraph` en los 6 métodos de listado** | Suite verde con `open-in-view=false`, que es lo que lo prueba (ver abajo) |
| ✅ | **V10 — constraint de exclusión** (A3) | 4 tests de integración contra Postgres real |
| ✅ | **Mapeo `23P01` → 409** | 2 tests unitarios |
| ✅ | **M1 — `open-in-view=false`** | Suite verde |
| ✅ | **V11 — índices secundarios** (M2) | Flyway aplica |
| ✅ | **V12 — `CHECK` de montos** (M3, parcial) | Flyway aplica |

**Por qué el orden A2 → M1 importó, y por qué la suite lo confirma:** con `open-in-view=true` un acceso lazy sin fetch se resuelve igual en la capa web y el N+1 queda escondido. Al apagarlo, una asociación no traída por el `@EntityGraph` habría reventado con `LazyInitializationException`. Que los 416 tests sigan verdes **con la property ya en `false`** es la evidencia de que el fetching quedó completo.

### Pendiente

1. **`EXPLAIN ANALYZE` sobre datos reales.** Este informe razona sobre código y esquema, no sobre mediciones. Con tráfico real el planner es la única autoridad: si algún índice de V9/V11 no se usa, sacarlo — un índice no usado es costo de escritura puro.
2. **Decidir `sena_pagada <= precio_total` con el negocio.** Lo dejé deliberadamente fuera de V12: parece obvio, pero no está validado en Java y no encontré la regla escrita. Si existe un caso legítimo (ajuste, recargo, propina), el `CHECK` tiraría producción abajo. Es la única invariante de M3 que quedó sin respaldo.
3. **Cerrar el hueco de pool (opcional).** El `EXCLUDE` no cubre el choque cancha-lógica vs. cancha-física, que sigue dependiendo del lock. Requiere desnormalizar; solo vale la pena si el negocio empieza a usar canchas compuestas en serio.

### No hacer

**G1 (batching), G2 (agregación en memoria), G3.** Gold-plating, con el razonamiento en la sección correspondiente. G1 en particular: la solución aparentemente obvia (`batch_size`) no haría nada mientras el ID sea `IDENTITY`.

---

### Nota de método

Lo verificado es que **el esquema aplica y es coherente con las entidades** (Flyway + `ddl-auto=validate` contra Postgres 16), que **el constraint se comporta como se diseñó** (4 casos, incluidos los dos rebookings que NO debe bloquear) y que **nada se rompió** (416 tests).

Lo que **no** está medido es la mejora de performance: los impactos de la sección 2 son consecuencias estructurales (Seq Scan sobre tabla que crece, round-trips por fila, lock retenido durante un escaneo), no resultados de benchmark. El punto 1 de "Pendiente" existe para cerrar esa brecha.
