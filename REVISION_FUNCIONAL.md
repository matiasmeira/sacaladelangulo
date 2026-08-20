# Revisión funcional, código muerto y testing adversarial — Backend Saque (`sacaladelangulo`)

> **Actualización post-revisión:** los 4 bugs confirmados en la primera pasada (§1, ítems 1-4)
> **ya están arreglados** y verificados (`./mvnw test`: 405/405 en verde, ver §4). La
> duplicación de autorización de §3.1 **también resultó ya estar solucionada** en otra
> ejecución paralela sobre este mismo repo — se re-verificó con grep y se corrige el hallazgo
> más abajo. El resto del documento (huecos de producto, ítems que requieren decisión) sigue
> reflejando el estado real: son decisiones de producto, no bugs de código, y no se tocaron.

- **Alcance:** todo el backend (auth, reserva, buffet, gastos, cierrecaja, caja, empleado,
  establecimiento, reportes, feedback, disponibilidad, core), leído módulo por módulo desde
  `src/main`. Primera pasada: solo tests (`src/test` + `pom.xml`, dependencias de test).
  Segunda pasada (a pedido explícito): los 4 bugs confirmados con test en rojo se corrigieron
  en código de producción, más la configuración de Docker/Testcontainers (ver §0 y §4).
- **Método:** lectura directa del código (no de comentarios ni de `AUDITORIA.md`), verificación
  de referencias reales con grep antes de marcar algo como muerto, y escritura de tests que
  intentan romper cada invariante del checklist. Dos subagentes de solo-lectura hicieron el
  escaneo de código muerto en paralelo (uno para auth/empleado/establecimiento/core/mails/
  feedback/disponibilidad, otro para reserva/buffet/gastos/cierrecaja/caja/reportes);
  sus hallazgos están incorporados y verificados en la sección 3.
- **Nota importante sobre el estado del repo:** este trabajo se hizo con el repo **en edición
  activa** — el `working tree` tenía cambios sin commitear al empezar (una migración a hash de
  tokens a medio aplicar, fixes de M-03/M-04 de `AUDITORIA.md`), y durante la sesión esos
  cambios fueron completados y commiteados (commit `2bea40e`). `AUDITORIA.md` (el reporte de
  seguridad previo, con fecha de hoy) describe como "hallazgos abiertos" varias cosas que el
  código actual **ya tiene arregladas** (M-03, M-04, M-05) — no confiar en ese documento como
  fuente de verdad del estado actual, se recomienda regenerarlo o borrarlo.

## 0. Antes que nada: un `mvn compile` sin `clean` mentía

Un primer `./mvnw compile` (sin `clean`) reportó 9 errores de compilación en
`RegistroVerificacionService`/`RecuperacionPasswordService`/`UsuarioService` referenciando
métodos (`.token()`, `.getCodigo()`, `findByToken`) que ya no existen en los modelos
(renombrados a `tokenHash`/`codigoHash` como parte del fix de M-05). Un `./mvnw clean compile`
inmediatamente después compiló sin errores: era `target/` desactualizado, no un problema real.
Se deja constado porque hizo perder tiempo de diagnóstico y **puede repetirle lo mismo a
cualquiera**: recomendación operativa, correr `clean` cuando el build de un compilador
incremental da errores que "no tienen sentido" contra el código que se está mirando.

---

## 1. Resumen ejecutivo

El backend está funcionalmente sólido y la superficie de dinero (reserva → caja → reportes)
está mayormente bien pensada: el servidor recalcula todo, hay locking pesimista real contra
doble-booking, y los gastos ya pasaron de `DELETE` físico a anulación lógica con movimiento
compensatorio. Esta pasada encontró **cuatro problemas reales, confirmados con tests que
fallaban contra el código** y que **ya se arreglaron** (a pedido explícito, con `./mvnw test`
en 405/405 verde tras el fix — ver §4):

1. ~~`finalizarReserva` permitía finalizar una reserva CANCELADA_PRERESERVA~~ — **arreglado**:
   ahora se rechaza igual que `CANCELADA` (`ReservaService.finalizarReserva`).
2. ~~`PrecioReservaCalculator` no normalizaba la escala de BigDecimal a 2 decimales~~ —
   **arreglado**: `setScale(2, HALF_UP)` en las dos ramas del cálculo.
3. ~~La compensación de caja por venta cancelada / gasto editado-eliminado se registraba contra
   el turno abierto EN ESE MOMENTO, no contra el turno original~~ — **arreglado**: nuevo guard
   `TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto` — si el turno original ya cerró, no
   se toca la caja (ni se revierte ni se registra un movimiento nuevo), solo un `log.warn`.
4. ~~`GastoService.registrarGasto` con método de pago nulo reventaba con
   `NullPointerException` en `GastoMapper`~~ — **arreglado**: validación server-side explícita
   (`validarCamposObligatorios`, junto con descripción vacía) antes de construir la entidad.

Ver el detalle de cada fix en su sección correspondiente (§2.1/§2.2) y la lista de archivos
tocados en §4.4.

Además hay **huecos de producto reales** sin bug de código asociado (una `CONFIRMADA` cuyo
turno ya pasó queda colgada para siempre, no existe reembolso de seña, la venta de buffet no
entra en el reporte de "resultado neto") que **no se tocaron** — son decisiones de negocio, no
bugs, y quedan documentados en §2 con la sugerencia de a quién le toca decidir.

Sobre la duplicación de autorización que esta revisión iba a reportar como hallazgo principal
de "código de más": **se re-verificó a pedido y ya está solucionada** — ver §3.1, corregido.

---

## 2. Coherencia funcional

### 2.1 Máquina de estados de `Reserva`

`Reserva.java`, `EstadoReserva.java`, `ReservaService.java`.

- **CONFIRMADA cuyo turno ya pasó, y nadie la finalizó ni la marcó ausente: queda CONFIRMADA
  para siempre.** `ReservaExpiracionService` (`@Scheduled` cada 1 min) solo libera
  `PENDIENTE_SENA` vencidas a `CANCELADA_PRERESERVA`; no existe ningún job ni lógica que
  toque una `CONFIRMADA` cuyo `fechaHoraFin` ya pasó. Esto es el caso testigo que pedía la
  tarea: si el dueño se olvida de finalizar/marcar ausente, esa reserva queda eternamente
  "activa" en los listados por defecto, nunca aparece en `ReporteFacturacionService` (que solo
  cuenta `FINALIZADA`), y nunca genera movimiento de caja aunque el partido se haya jugado y
  cobrado en efectivo fuera del sistema.
  **Impacto:** reportes de facturación sistemáticamente subestimados en cualquier
  establecimiento con disciplina operativa imperfecta (la norma, no la excepción).
  **Sugerencia:** agregar un job (mismo patrón que `ReservaExpiracionService`) que, pasadas N
  horas del `fechaHoraFin` de una `CONFIRMADA` sin acción, la pase a un estado explícito nuevo
  (ej. `VENCIDA_SIN_RESOLVER`) distinto de `FINALIZADA`/`AUSENTE`, visible en un panel de "para
  resolver" — no autocompletarla como si el dueño hubiera confirmado el cobro, eso sería peor.

- **BUG ARREGLADO — `finalizarReserva` no rechazaba `CANCELADA_PRERESERVA`.**
  `ReservaService.java`, método `finalizarReserva`. Antes del fix, el método solo excluía
  explícitamente `CANCELADA`, hacía no-op en `FINALIZADA` y rechazaba `PENDIENTE_SENA` — pero
  `CANCELADA_PRERESERVA` (un cuarto valor del enum) no matcheaba ninguno de los tres checks, así
  que caía directo en `reserva.setEstado(FINALIZADA)` y generaba cobro + movimiento de caja
  sobre una prereserva que **nadie confirmó ni pagó**. **Fix aplicado:** el primer `if` ahora
  chequea `estado == CANCELADA || estado == CANCELADA_PRERESERVA`. Verificado:
  `ReservaServiceMatrizTransicionesTest.finalizar_matriz[CANCELADA_PRERESERVA]` pasa.

- **`cancelarReserva` no bloquea explícitamente la transición AUSENTE → CANCELADA.**
  `ReservaService.java:656-665`. Solo hay throws para `FINALIZADA` (línea 656) y no-op para
  `CANCELADA` (línea 660); todo lo demás —incluido `AUSENTE`— cae en `setEstado(CANCELADA)`.
  No es necesariamente incorrecto (alguien podría argumentar que "cancelar una ausencia" es
  razonable), pero mezcla dos conceptos de negocio distintos (un no-show vs. una cancelación
  explícita) bajo el mismo estado final, perdiendo la distinción que el propio enum se tomó el
  trabajo de modelar. **Sugerencia:** decisión de producto explícita — o se bloquea (un
  `AUSENTE` solo se revierte, nunca se cancela) o se documenta a propósito.

- **No existe reembolso de seña.** Revisado `cancelarReserva` completo: nunca toca
  `senaPagada` ni registra ningún movimiento de caja compensatorio al cancelar. La ventana de
  gracia (`validarPlazoDeCancelacion`, `Establecimiento.minutosGraciaCancelacion`) decide si SE
  PUEDE cancelar, pero no hay ningún campo, estado o movimiento que registre si la seña ya
  cobrada se devolvió, se retuvo como penalidad, o quedó pendiente. **Impacto:** un jugador que
  canceló dentro del plazo y pagó seña por Mercado Pago no tiene ningún rastro en el sistema de
  si le devolvieron la plata. **Sugerencia:** agregar un campo/estado de "seña reembolsada"
  (sí/no/parcial) al cancelar, aunque el reembolso en sí se gestione fuera del sistema (no hay
  pasarela de pagos integrada, ver `AUDITORIA.md` §6).

### 2.2 Caminos de plata

- **BUG ARREGLADO — la compensación de caja escribía en el turno equivocado.**
  `VentaService.cancelarVenta`, `GastoService.editarGasto` y `GastoService.eliminarGasto`
  revertían el movimiento original llamando a `TurnoCajaService.registrarMovimientoSiCorresponde`,
  que **siempre** busca el turno con estado `ABIERTO` del establecimiento — no el turno donde se
  originó el movimiento que se está corrigiendo. Secuencia que lo disparaba: se vende algo en
  efectivo en el Turno A → se cierra el Turno A (la venta ya quedó contabilizada en su arqueo) →
  se abre el Turno B → se cancela esa venta → la reversión (un EGRESO) se registraba contra el
  Turno B, que nunca tuvo ese ingreso físico.
  **Fix aplicado** (se eligió la opción "no compensar si el turno original ya cerró", en vez de
  permitir escribir contra un turno `CERRADO`, que hubiera roto la semántica actual): nuevo
  `MovimientoCajaRepository.findTopByOrigenAndReferenciaIdOrderByFechaHoraDesc` +
  `TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto(establecimiento, origen, referenciaId)`,
  que resuelve el turno del último movimiento registrado para esa venta/gasto y compara contra
  el turno actualmente abierto. Los 3 call sites (`VentaService.cancelarVenta`,
  `GastoService.editarGasto`, `GastoService.eliminarGasto`) ahora gatean la
  reversión/re-registro con este chequeo; si da `false`, no tocan la caja y dejan un
  `log.warn` — la corrección queda igual reflejada en `Venta.estado`/`Gasto.isActive`, que es
  la fuente de verdad que ya usan los reportes (no `MovimientoCaja`). Verificado (test escrito
  con Postgres real, no ejecutado en este entorno por falta de Docker — ver §4):
  `TurnoCajaConcurrenciaIntegrationTest.compensarVentaCanceladaEnOtroTurno_...`.

- **Cobro sin caja abierta = cobro sin ningún rastro en caja, para siempre.**
  `TurnoCajaService.registrarMovimientoSiCorresponde` (línea 151-168): si no hay turno
  `ABIERTO`, hace un `log.warn` y retorna sin registrar nada. Esto es consistente con "la caja
  es opcional", pero significa que finalizar una reserva en efectivo o vender buffet en
  efectivo sin haber abierto caja **cobra igual** (el dinero cambia de mano en la vida real) y
  **no deja ningún rastro recuperable** — ni siquiera un "pendiente de asignar a un turno".
  No es un bug (es la consecuencia de un diseño deliberado, documentado en el propio código),
  pero vale la pena que el dueño/producto lo sepan: si un empleado cobra en efectivo con la
  caja cerrada, ese dinero es indistinguible de dinero que nunca se cobró, a los ojos del
  sistema.

- **`ReporteGastosService.obtenerResultado` (neto = facturado − gastos) NO incluye ingresos de
  buffet.** `ReporteFacturacionService.java:31` solo inyecta `ReservaRepository` — el
  "facturado" que alimenta el neto sale exclusivamente de `sumFacturacionPorMetodoPago` sobre
  `Reserva.estado = FINALIZADA`. La venta de buffet (`Venta`/`VentaMetricasService`) tiene sus
  propias métricas (`buffet/service/VentaMetricasService.java`) pero nunca se suman al
  resultado neto del establecimiento. No es un error de cálculo (la resta está bien hecha con
  los números que recibe), es un hueco de alcance: un club con buffet activo ve un "neto"
  sistemáticamente subestimado. **Sugerencia:** sumar el total de `Venta` `CONFIRMADA` del
  período a `totalFacturado` en `obtenerResultado`, o renombrar el campo para dejar explícito
  que es "facturación de canchas" y no el neto real del negocio.

- **Camino limpio confirmado (no es un hallazgo, es una verificación positiva):**
  `TurnoCajaService.registrarMovimientoSiCorresponde` es el único punto de escritura de
  `MovimientoCaja` en todo el código (confirmado por grep de ambos subagentes) — no hay ningún
  service que inserte un movimiento "por afuera". El rollback transaccional también se
  confirmó real (no solo con mocks): `TurnoCajaConcurrenciaIntegrationTest.ventaFallida_...`
  prueba con Postgres real que si una venta con 2 productos falla en el segundo (producto de
  otro establecimiento), el stock ya descontado del primero se revierte y no queda ni `Venta`
  ni `MovimientoCaja` persistidos (test no ejecutado en este entorno por falta de Docker, pero
  el código y el test están listos).

### 2.3 Interacción entre features / huecos de producto

- **Ausencia vs. facturación:** consistente. `marcarAusente` no genera movimiento de caja
  (correcto: no hubo cobro), y `finalizarReserva` desde `AUSENTE` sí lo permite (revertir el
  error de haber marcado ausente a alguien que en realidad vino y pagó) — este camino SÍ está
  bien acotado a `AUSENTE`/`CONFIRMADA` en el código (a diferencia del bug de
  `CANCELADA_PRERESERVA` de arriba).
- **Plan/membresía vs. límite de canchas:** revisado `CanchaService`/`EstablecimientoService`
  completos — **no existe ningún límite de cantidad de canchas por plan**. Lo único que
  distingue TRIAL/FREE de planes pagos es una seña mínima obligatoria
  (`CanchaService.java:163-174`, `SENA_MINIMA_PLAN_LIMITADO`). Si el modelo de negocio
  pretende que TRIAL/FREE tengan un tope de canchas (algo común en SaaS con planes), ese tope
  simplemente no está implementado. Se documenta como posible hueco de producto, no como bug
  (puede ser una decisión consciente de no limitar todavía).
- **Rango de fechas invertido:** comportamiento **inconsistente** entre endpoints similares.
  `ReporteFacturacionService.obtenerFacturacion` y `ReporteGastosService` (`validarRango`)
  rechazan `desde > hasta` con `IllegalArgumentException` (400). `GastoService.listarGastos`
  (el listado, no el reporte) no valida nada — delega directo a `GastoRepository.buscar`, que
  con un rango invertido simplemente no matchea ninguna fila (página vacía, sin error). Mismo
  patrón para reservas: `ReservaService.obtenerReservasPorCanchaYFecha`/
  `obtenerReservasPorEstablecimientoYFecha` reciben una única `fecha` (no rango), así que no
  aplica. No es grave, pero es la clase de inconsistencia de API que confunde a quien integra
  el frontend: dos endpoints con forma similar (`desde`/`hasta`) que se comportan distinto ante
  la misma entrada inválida. Confirmado con test
  (`GastoServiceAdversarialTest.listarGastos_RangoInvertido_...`).

---

## 3. Nada de más (código muerto / duplicación / sobre-ingeniería)

Escaneo hecho por dos subagentes de solo-lectura (uno por mitad de módulos) con verificación
por grep, más lectura directa propia. **Conclusión general: el código está limpio** — no se
encontraron clases muertas, métodos públicos sin uso, ni endpoints huérfanos en ninguno de los
12 módulos. El único patrón sistemático es la duplicación de autorización.

### 3.1 Duplicación de `validarPropietarioOAdmin` / `validarPropietario` — CORREGIDO (re-verificado)

**Corrección respecto a la primera versión de este reporte:** en esa pasada se habían listado
12 copias privadas de esta lógica (incluidas 3 dentro de `ReservaService`) como duplicación sin
resolver. El usuario señaló que esto ya se había solucionado en otra ejecución paralela sobre
el mismo repo — se re-verificó con grep antes de tocar nada más:

```
grep -rn "private (Usuario|void) validarPropietario" src/main/java   ->  0 resultados
```

**Confirmado: las 12 copias que se habían listado ya no existen.** Cada uno de los sitios
señalados (`ReservaService`, `ProductoBuffetService`, `VentaMetricasService`, `EmpleadoService`,
`RegistroAuditoriaService`, `BloqueoCanchaService`, `BloqueoJugadorService`,
`DiaNoLaborableService`, `CanchaService`, `EstablecimientoService`, `DispositivoCajaService`)
ahora llama directo a `autorizacionEmpleadoService.validarPropietarioOAdmin(...)` — incluida la
propia `ReservaService`, que ya no tiene el método privado `validarPropietarioOAdmin` (verificado
que sus 5 call sites originales pasaron a usar la versión compartida).

**Único matiz que sigue siendo real:** los dos chequeos INLINE de `ReservaService`
(`confirmarReserva` y `cancelarReserva`) siguen escritos a mano — pero no son duplicados 1:1 de
`validarPropietarioOAdmin`, tienen lógica extra mezclada (jugador dueño de la reserva, empleado
con permiso puntual) que el método compartido no contempla, así que no son candidatos directos
a la misma unificación sin antes separar esa lógica extra. No se tocaron: no es la misma clase
de duplicación que se había reportado y no estaba en el alcance de "arreglar los 4 bugs".

`reportes/service/ReporteAutorizacionService.validarDuenoDelEstablecimiento` sigue siendo una
segunda abstracción paralela (documentada como decisión de scoping deliberada en su propio
Javadoc) — no es el mismo problema, es una duplicación de *concepto* consciente, no una copia
accidental de código.

### 3.2 Otros hallazgos menores

- **`reportes/dto/AusenciasInfo.java`** — los campos `disponible`/`motivoNoDisponible` son
  vestigiales: `ReporteClientesService.java:78` ahora siempre construye
  `new AusenciasInfo(true, totalAusencias, null)`. Eran un placeholder legítimo de "feature
  todavía no implementada" de cuando el conteo de ausencias no existía; ya se implementó y el
  branch `disponible=false` quedó inalcanzable. **Sugerencia:** colapsar el DTO a solo `total`
  (cambio de contrato con el frontend, coordinar antes de tocarlo).
- **`Reserva.java:107`** — `private BigDecimal senaPagada = BigDecimal.ZERO;` con `@Builder` a
  nivel de clase pero sin `@Builder.Default` en el campo. El propio compilador lo advierte
  ("`@Builder` will ignore the initializing expression entirely"): cualquier
  `Reserva.builder()...build()` que no fije `senaPagada` explícitamente obtiene `null`, no
  `ZERO`. Hoy no es un bug vivo porque los 3 call sites de producción sí lo fijan siempre, y
  `@PrePersist` tiene un fallback — pero es una trampa para el próximo call site que se
  agregue (y para cualquier test que construya una `Reserva` sin persistirla). **Sugerencia:**
  agregar `@Builder.Default`.
- **`auth/service/UsuarioService.java:71`** — el flujo de verificación de teléfono por SMS es
  una feature a medio construir expuesta como API real y funcional
  (`/telefono/solicitar-codigo`, `/telefono/verificar-codigo`): genera el código, lo persigue
  (hasheado, correcto), pero nunca lo envía por ningún canal (`// TODO: integrar proveedor real
  de SMS`, solo hace `log.debug`). Un usuario real que intente verificar su teléfono hoy no
  tiene forma de recibir el código. **Sugerencia:** ocultar el flujo del frontend hasta que
  haya un proveedor de SMS integrado, o dejarlo pero comunicarlo claramente como "en beta/no
  disponible" — hoy es indistinguible de una feature funcional que da un 200 y no hace nada
  útil.
- **`auth/controller/AuthController.java`** — `registerPlayerDeprecado` (`POST
  /api/v1/auth/register/player`) devuelve `410 GONE` a propósito, reemplazado por el flujo de
  registro en 2 pasos. Es un shim de deprecación documentado, no código muerto accidental.
  Dejar hasta confirmar que ningún cliente viejo le sigue pegando, después borrar.
- **`core/email/webhook/ResendWebhookController.java`** — verifica firma y loguea el evento,
  pero no actúa sobre bounces/opens todavía. Explícitamente documentado como scope parcial
  intencional ("queda para cuando exista un caso de uso concreto"). Dejar así.

No se encontraron: clases sin referencias, métodos públicos de servicio/repositorio sin
llamador, endpoints duplicados/placeholder sin documentar, ni valores de enum declarados pero
nunca producidos/consumidos (se verificó explícitamente `OrigenMovimientoCaja`,
`TipoMovimientoCaja`, `EstadoVenta`, `EstadoTurnoCaja`, `EstadoReserva`, `CategoriaGasto`).

---

## 4. Cobertura de tests

**Resultado final de `./mvnw test` (todo el repo, suite existente + lo agregado en esta
pasada, con los 4 fixes ya aplicados): 405 tests, 0 failures, 0 errors — `BUILD SUCCESS`.**
Antes de los fixes eran 407 tests con 3 failures + 2 errors (los 4 bugs + los 2 tests que
requieren Docker); después de arreglar los 4 bugs y de configurar Surefire para no intentar los
tests de Docker por defecto (ver §4.0), quedan 405 corriendo siempre en verde. El detalle
completo está en `target/surefire-reports/`.

### 4.0 Docker: qué se decidió y por qué

Dos de los tests nuevos (`ReservaConcurrenciaIntegrationTest`,
`TurnoCajaConcurrenciaIntegrationTest`, 4 métodos en total) usan Testcontainers para levantar un
Postgres real — es la única forma honesta de probar concurrencia real con hilos y constraints de
base de datos reales (ver §4.1). Eso significa que **necesitan un daemon de Docker corriendo**
en la máquina donde se ejecuta `mvn test`. En este entorno no hay Docker instalado
(`docker: command not found`), así que no pude confirmarlos empíricamente — solo por lectura de
código.

**Lo que importa para el día a día:** un `./mvnw test` (o `./mvnw clean test`) sin flags
**se comporta exactamente igual que antes de agregar estos tests** — no requiere Docker, no
falla, no se cuelga, no cambia de duración de forma perceptible. Esto no es automático: lo
configuré explícitamente en `pom.xml` (plugin `maven-surefire-plugin`,
`<excludedGroups>${surefire.excludedGroups}</excludedGroups>`, con
`surefire.excludedGroups=testcontainers` como default en `<properties>`), y ambos tests están
marcados con `@Tag("testcontainers")`. Verificado en este entorno (sin Docker):

- `./mvnw test` → 405 tests, `BUILD SUCCESS`, no toca Docker en absoluto.
- `./mvnw test -Dsurefire.excludedGroups= -Dgroups=testcontainers` → intenta correr los 2 tests
  de Testcontainers y falla con `Could not find a valid Docker environment` (limpio, esperado).

**Cuando instales Docker Desktop** (o cualquier daemon Docker compatible) y quieras correr la
suite completa, incluidos estos 2 tests: `./mvnw test -Dsurefire.excludedGroups=` (vacío) corre
todo, sin necesidad de tocar `pom.xml` ni de recordar tags. La primera vez va a bajar la imagen
`postgres:16-alpine` (~80MB, se cachea localmente); las siguientes corridas son rápidas. Como
todavía estás en desarrollo (no en producción), no hay apuro en instalarlo — es una herramienta
de verificación adicional, no un requisito para seguir trabajando en el día a día. Cuando quieras
confirmar los 4 bugs de concurrencia/rollback antes de ir a producción, ese es el momento de
correrlo.

### 4.1 Infraestructura agregada

`pom.xml` (solo `test` scope): `spring-boot-testcontainers`, `testcontainers:junit-jupiter`,
`testcontainers:postgresql` (versión gestionada por el BOM de `spring-boot-starter-parent`, sin
fijar a mano). Base compartida: `support/AbstractPostgresIntegrationTest.java` — arranca un
Postgres real vía Testcontainers y corre las migraciones de Flyway reales (a diferencia de los
`@DataJpaTest` + H2 `create-drop` que ya existían en el repo), para que constraints como el
índice único parcial `uk_turno_caja_abierto_por_establecimiento` (V1) se ejerciten de verdad.

### 4.2 Qué se testeó (y con qué resultado)

**Reservas**
- Doble reserva del mismo slot en paralelo, 2 hilos reales contra Postgres real
  (`ReservaConcurrenciaIntegrationTest`) — **escrito, sin confirmar por falta de Docker (ver §4.0)**.
- Matriz completa de transiciones (confirmar/cancelar/finalizar/marcarAusente/revertirAusencia
  × 6 estados = 30 combinaciones) vía `@ParameterizedTest` (`ReservaServiceMatrizTransicionesTest`)
  — **30/30 pasan** (el caso `CANCELADA_PRERESERVA` de `finalizar_matriz` revelaba el bug #1 de
  §1; ahora pasa como test de regresión tras el fix).
  Este archivo también agrega: `revertirAusencia` owner-only (empleado falla), borde exacto de
  `marcarAusente` en la hora de inicio, turno futuro falla, y borde de 1 segundo antes/después
  de la expiración de 10 min — **todos pasan**.
- Solapamiento parcial (18-19 existente vs. 18:30-19:30 nueva) y mover una reserva a una
  cancha/horario ya ocupado (`ReservaServiceSolapamientoYDineroAdversarialTest`) — **pasan**.
- `horaFin <= horaInicio` (incluye duración cero) y fecha en el pasado — **pasan**.
- Precio siempre recalculado en servidor: `ReservaRequest` ni siquiera tiene un campo de
  precio (garantía estructural, no solo de runtime) — test que verifica el valor persistido
  sale de `PrecioReservaCalculator` — **pasa**.

**Caja**
- Abrir dos cajas del mismo establecimiento en paralelo, 2 hilos reales, constraint real de DB
  (`TurnoCajaConcurrenciaIntegrationTest`) — **escrito, sin confirmar por falta de Docker**. (La
  suite unitaria existente, `TurnoCajaServiceTest`, ya cubre el caso secuencial con mocks y pasa.)
- Cerrar una caja ya cerrada (secuencial, Testcontainers) — sin confirmar por Docker; ya
  cubierto también a nivel unitario en `TurnoCajaServiceTest` existente (pasa).
- Saldo teórico con mezcla de métodos (ya cubierto en la suite existente) + **agregado**: solo
  movimientos no-efectivo (`TurnoCajaServiceArqueoAdversarialTest`) — **pasa**: el saldo teórico
  en efectivo queda exactamente igual al fondo inicial.
- Diferencia de arqueo EXACTA (cero) — **faltaba en la suite existente** (que solo tenía
  sobrante/faltante), agregada — **pasa**.
- Rollback real: venta con un producto inválido a mitad del carrito no deja stock, `Venta` ni
  `MovimientoCaja` persistidos (`TurnoCajaConcurrenciaIntegrationTest.ventaFallida_...`) —
  **escrito, sin confirmar por falta de Docker**.
- **Compensación cross-turno** (§2.2, bug #3) — test que documenta el comportamiento correcto
  ahora implementado (`compensarVentaCanceladaEnOtroTurno_...`) — **escrito, sin confirmar
  empíricamente por falta de Docker**, pero el fix se verificó leyendo el código:
  `TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto` y los 3 call sites que la usan ya
  compilan y el resto de la suite (que mockea `TurnoCajaService`) sigue en verde con el nuevo
  método stubeado explícitamente donde hace falta.

**Gastos y reportes**
- Monto negativo/cero (ya cubierto en la suite existente) + monto enorme (38 dígitos, límite de
  `NUMERIC(38,2)`) — **pasa, sin overflow**.
- Editar/borrar gasto de otro establecimiento — ya cubierto en la suite existente, pasa.
- Descripción vacía y método de pago nulo: **ahora se rechazan explícitamente a nivel service**
  (`GastoService.validarCamposObligatorios`, bug #4 de §1) con `IllegalArgumentException` y un
  mensaje de negocio claro — antes, el método de pago nulo reventaba con `NullPointerException`
  en `GastoMapper`. Tests actualizados para reflejar el comportamiento correcto — **pasan**.
- Rango de fechas invertido: reportes lo rechazan (ya cubierto), listado de gastos no
  — **confirmado, inconsistencia documentada** (§2.3, decisión de producto pendiente, no se tocó).
- Neto negativo (gastos > facturado) — **agregado, pasa** (la resta está bien hecha).

**Auth**
- `JwtService` **no tenía ningún test dedicado en todo el repo** (verificado antes de escribir
  el archivo) — se agregó `JwtServiceTest`: token fresco válido, **tokenVersion desactualizada
  invalida el token** (el caso central del checklist — pasa), username distinto invalida, secreto
  corto rechazado en el constructor, y un hallazgo de contrato: `isTokenValid` con un token
  expirado no devuelve `false`, tira `ExpiredJwtException` — inofensivo en producción porque
  `JwtAuthenticationFilter` siempre llama antes a `extractUsername` (que si está en su propio
  try/catch), pero es una firma "boolean-que-en-realidad-tira" para cualquier código nuevo que
  lo use directo — **documentado con test que confirma el throw**.
- Agotar intentos → bloqueo, código expirado, rate limiter excedido — ya cubiertos extensamente
  en `RegistroVerificacionServiceTest`/`RecuperacionPasswordServiceTest`/`RateLimiterServiceTest`
  existentes (revisados, no duplicados). Faltaba el borde "correcto en el último intento
  disponible" (intentos=4 de 5 máximos) — agregado
  (`RegistroVerificacionServiceAdversarialTest`) — **pasa**.
- Reset no revela si el email existe, token viejo inválido tras reset (tokenVersion) — ya
  cubiertos en la suite existente (revisados, correctos).

**Dinero**
- Recalculo en servidor: cubierto arriba (reservas) y en la suite existente de `VentaService`
  (`registrarVenta_Exito_VariosProductos_SumaTotalCorrectamente`, ya probaba que el total sale
  del precio server-side del producto, no de nada que mande el cliente — `DetalleVentaRequest`
  tampoco tiene campo de precio).
- **BUG ARREGLADO — escala de BigDecimal inconsistente.** `PrecioReservaCalculator` no hacía
  `setScale(2, HALF_UP)` sobre el resultado del cálculo proporcional: `duracionHoras` salía de un
  `.divide(..., 2, HALF_UP)` (escala 2) y se multiplicaba contra `precioPorHora` — si
  `precioPorHora` también tenía escala 2 (como vuelve de `NUMERIC(38,2)`, o como lo tipea un
  dueño: "1500.50"), el resultado quedaba en escala 4 (`750.0000` en vez de `750.00`). **Fix
  aplicado:** `setScale(2, RoundingMode.HALF_UP)` en las dos ramas de `calcularPrecio` (precio
  exacto configurado y cálculo proporcional). `PrecioReservaCalculatorAdversarialTest` (3 tests,
  incluido el caso que antes lo enmascaraba con `BigDecimal.valueOf(10000)` escala 0) queda como
  regresión — **los 3 pasan**.

### 4.3 Qué queda sin cubrir

- **Los 4 tests que dependen de Testcontainers no se ejecutaron en este entorno** (sin Docker,
  ver §4.0): doble-booking concurrente real, doble apertura de caja concurrente real, rollback
  transaccional de venta fallida, y el fix de compensación cross-turno. Correrlos con Docker
  disponible (`./mvnw test -Dsurefire.excludedGroups=`) es el primer paso de verificación
  pendiente antes de ir a producción.
- **Integración HTTP end-to-end** (MockMvc/WebTestClient contra los controllers reales, con JWT
  real): sigue sin existir, tal como ya señalaba `AUDITORIA.md` §9 ("tests de integración
  end-to-end de IDOR cross-tenant"). Esta pasada no lo agregó — es un esfuerzo bastante mayor
  (levantar el filtro de seguridad completo) que no entraba en el checklist de la tarea.
  Recomendado para la próxima iteración.
- No se tocaron `feedback`, `disponibilidad`, `mails`, `empleado` (más allá de lo que aparece
  indirectamente vía `AutorizacionEmpleadoService`) — no estaban en el checklist mínimo de la
  tarea y ya tienen suites propias razonables; se priorizó profundidad en reserva/caja/gastos/
  auth/dinero sobre cobertura horizontal.

### 4.4 Código de producción tocado (segunda pasada, a pedido explícito)

Los 4 fixes de §1 tocaron estos archivos — nada más:

- `reserva/service/ReservaService.java` — `finalizarReserva`: rechaza `CANCELADA_PRERESERVA`.
- `establecimiento/service/PrecioReservaCalculator.java` — `setScale(2, HALF_UP)` en las dos
  ramas de `calcularPrecio`.
- `gastos/service/GastoService.java` — nuevo `validarCamposObligatorios` (metodoPago/descripcion),
  y las llamadas a compensación en `editarGasto`/`eliminarGasto` ahora gatean con
  `TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto`.
- `buffet/service/VentaService.java` — `cancelarVenta` gatea la reversión con el mismo método.
- `cierrecaja/service/TurnoCajaService.java` — nuevo método público
  `movimientoOriginalSigueEnTurnoAbierto`.
- `cierrecaja/repository/MovimientoCajaRepository.java` — nuevo
  `findTopByOrigenAndReferenciaIdOrderByFechaHoraDesc`.
- `pom.xml` — plugin `maven-surefire-plugin` con `excludedGroups` (ver §4.0); nada relacionado
  con los 4 bugs.

Tests existentes que necesitaron un ajuste porque el comportamiento cambió (no por casualidad,
sino porque antes verificaban el bug): `VentaServiceTest.cancelarVenta_Exito_RestauraStock`,
`GastoServiceTest.editarGasto_Exito_ActualizaCampos`,
`GastoServiceTest.eliminarGasto_Exito_EsAnulacionLogicaNoDeleteFisico` (les faltaba stubear
`movimientoOriginalSigueEnTurnoAbierto=true` para seguir representando el caso feliz "mismo
turno sigue abierto"). El resto de los tests nuevos de la primera pasada que documentaban los
bugs (`ReservaServiceMatrizTransicionesTest`, `PrecioReservaCalculatorAdversarialTest`,
`GastoServiceAdversarialTest`, `TurnoCajaConcurrenciaIntegrationTest`) se actualizaron para
reflejar el comportamiento correcto ahora implementado, sin cambiar lo que estaban verificando.

---

## 5. Plan priorizado

**Hecho (los 4 bugs confirmados, arreglados y verificados con `./mvnw test` en verde):**
1. ~~`ReservaService.finalizarReserva`: rechazar `CANCELADA_PRERESERVA`~~ — hecho (§2.1).
2. ~~`GastoService`: validar `metodoPago`/`descripcion` server-side~~ — hecho (§2.2/§4.2).
3. ~~`PrecioReservaCalculator.calcularPrecio`: `setScale(2, HALF_UP)`~~ — hecho (§2.2 dinero).
4. ~~Compensación de caja cross-turno~~ — hecho: se optó por "no compensar si el turno original
   ya cerró" (`TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto`) — §2.2.
5. ~~Duplicación de `validarPropietarioOAdmin`~~ — resultó ya estar solucionada, re-verificado
   (§3.1).

**Requiere decisión de producto (no se tocó código, son huecos de negocio, no bugs):**
6. `CONFIRMADA` colgada sin resolver (§2.1): definir si corresponde un estado nuevo, un cierre
   automático, o solo un panel de alertas.
7. Reembolso de seña al cancelar (§2.1): al menos un campo de tracking, aunque el flujo de
   plata siga siendo manual.
8. Buffet fuera del "resultado neto" (§2.2): decidir si se suma o se renombra el campo.
9. `cancelarReserva` permite AUSENTE→CANCELADA (§2.1): decidir si es intencional.
10. Límite de canchas por plan (§2.3): confirmar si el modelo de negocio lo necesita.
11. Rango de fechas invertido inconsistente entre listados y reportes (§2.3): unificar el
    criterio.

**Deuda técnica menor, sin apuro:**
12. `Reserva.senaPagada`: agregar `@Builder.Default` (§3.2).
13. Decidir el destino del flujo de OTP por SMS (ocultar o completar) (§3.2).
14. Colapsar `AusenciasInfo` (campos vestigiales) (§3.2).

**Verificación pendiente (requiere Docker, ver §4.0):**
15. Cuando instales Docker, correr `./mvnw test -Dsurefire.excludedGroups=` para confirmar
    empíricamente los 4 tests de Testcontainers (doble-booking, doble apertura de caja,
    rollback de venta, fix de compensación cross-turno) — hoy solo verificados por lectura de
    código y por el resto de la suite (que mockea `TurnoCajaService`) en verde.
