# Auditoría de seguridad adversarial — Canchear (`sacaladelangulo`)

**Fecha:** 2026-09-04 · **Alcance:** revisión estática del repo (435 archivos Java, 27 controllers, 48 services, 22 migraciones Flyway).
**Método:** lectura adversarial del código fuente. No se ejecutó nada contra ningún entorno ni se hicieron requests externos.

## Resumen ejecutivo

**No encontré ninguna vulnerabilidad crítica ni alta.** El aislamiento multi-tenant, el
recálculo server-side de montos y la máquina de estados de reservas —las tres superficies
donde un marketplace con plata real se rompe— están correctamente implementadas y son
consistentes en los 27 controllers.

Los 10 hallazgos son de severidad **media (2)**, **baja (7)** e **informativa (1)**. El más
relevante es una fuga de enumeración de cuentas en los dos endpoints públicos de registro,
que el propio código documenta como decisión deliberada.

| Severidad | Cantidad |
|---|---|
| Crítica | 0 |
| Alta | 0 |
| Media | 2 |
| Baja | 7 |
| Informativa | 1 |

> **Descartado tras revisión con el autor:** el hallazgo original M-03 señalaba que
> `crearReservaSemanal` acepta un `jugadorId` arbitrario y saltea el chequeo de jugador
> bloqueado y el de teléfono verificado. **Es intencional**: el turno fijo lo carga el dueño a
> mano y lo cancela si no prospera, así que ambas políticas son suyas y puede overridearlas
> deliberadamente. Lo que sí quedó del análisis es el fan-out de notificaciones, que se
> reclasificó como **B-07**.

---

# MEDIA

## M-01 — Enumeración de cuentas en los endpoints públicos de registro

> **❌ DESCARTADO (2026-09-05) — decisión de producto.** El flujo del front ya revela lo
> mismo por diseño: al tipear el email, si la cuenta existe pide contraseña y si no existe
> manda el mail de verificación. Ocultarlo en la API no cerraría el oráculo mientras la UX
> lo exponga, así que el hallazgo no aplica. Es el mismo modelo que usan Google o Slack.
>
> **Residual que la decisión NO cubría: ✅ CERRADO (2026-09-05).**
> `/api/v1/auth/registro/iniciar` no tenía límite por IP, así que rotando direcciones se lo
> podía usar para mandar correo no solicitado desde el dominio propio. Se agregó a
> `RateLimitFilter.LIMITES_POR_RUTA` con **10 cada 10 min por IP** — más holgado que el 5/10min
> de `register/owner` porque es el camino de alto volumen y detrás de un CGNAT muchos usuarios
> legítimos comparten IP. Cubierto por `RateLimitFilterTest.doFilter_RegistroIniciar_EstaLimitadoPorIp`.

* **Severidad:** Media (CVSS ~5.3 · AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N)
* **Archivos:** `RegistroVerificacionService.java:99-101` y `AuthService.java:64-66`

```java
// RegistroVerificacionService.iniciarRegistro
if (usuarioRepository.existsByEmail(email)) {
    throw new IllegalArgumentException("El email ya está registrado");   // ← oráculo
}
```

### Vector

Los dos endpoints son `permitAll` y responden distinto según exista o no la cuenta:

```http
POST /api/v1/auth/registro/iniciar
Content-Type: application/json

{"email":"victima@gmail.com"}
```

* Cuenta existente → `400 {"error":"El email ya está registrado"}`
* Cuenta inexistente → `200 OK` (y le llega un mail de verificación al titular)

Idéntico en `POST /api/v1/auth/register/owner`. Un atacante confirma qué direcciones de una
lista tienen cuenta en Canchear, y las usa para credential stuffing dirigido o phishing
("tu reserva en Canchear…"). El efecto secundario es peor que el oráculo: cada sondeo de un
email **no** registrado dispara un correo real desde el dominio del negocio, lo que convierte
el endpoint en un relay de mails no solicitados a direcciones elegidas por el atacante.

El rate limit acota el volumen pero no cierra el oráculo: 3 intentos / 15 min **por email**
(`registro-email:<email>`) es un presupuesto por objetivo, no global, así que verificar 10.000
direcciones distintas no consume ningún límite compartido. El límite por IP
(`/register/owner`: 5 / 10 min) sí molesta, pero `/registro/iniciar` **no figura en
`RateLimitFilter.LIMITES_POR_RUTA`** — no tiene límite por IP en absoluto.

### Contexto honesto

Esto es una **decisión deliberada y documentada**, no un descuido. El comentario de
`RecuperacionPasswordService.java:70-79` lo dice explícitamente: *"a diferencia del registro,
acá SÍ importa no filtrar esa información porque es la cuenta de otra persona la que está en
juego, no la propia"*. El razonamiento se sostiene para el caso feliz (registrás tu propio
email), pero no contempla al atacante que sondea el email **ajeno**.

Vale registrar que la recuperación de contraseña sí está bien resuelta: siempre 200.

### Fix propuesto

Convertir el registro al mismo patrón que ya usa la recuperación en este repo: responder
siempre `200` y decidir por email.

```java
// RegistroVerificacionService.iniciarRegistro
if (usuarioRepository.existsByEmail(email)) {
    // Mismo criterio que RecuperacionPasswordService.solicitarRecuperacion: se retorna
    // normalmente y el controller responde 200. Al titular le llega un mail de
    // "ya tenés cuenta, ingresá o recuperá tu contraseña" en vez del de verificación.
    log.info("Solicitud de registro para un email ya registrado");
    eventPublisher.publishEvent(new RegistroSobreCuentaExistenteEvent(email));
    return;
}
```

Y agregar `/api/v1/auth/registro/iniciar` a `RateLimitFilter.LIMITES_POR_RUTA` (por IP), que
hoy sólo cubre `/register/owner`.

Para `registerOwner` el cambio es más invasivo porque devuelve un JWT de una sola pasada. La
opción coherente es migrarlo al flujo de 2 pasos que ya existe para PLAYER; si eso es mucho
para ahora, al menos igualar el mensaje con un `400` genérico ("No se pudo completar el
registro con esos datos") que no distinga la causa.

### Test que demuestra la vulnerabilidad

```java
// src/test/java/.../auth/service/RegistroVerificacionServiceAdversarialTest.java
@Test
void iniciarRegistro_noRevelaSiElEmailYaEstaRegistrado() {
    // Falla HOY: lanza IllegalArgumentException("El email ya está registrado").
    when(usuarioRepository.existsByEmail("ocupado@test.com")).thenReturn(true);
    when(rateLimiterService.tryConsume(anyString(), anyInt(), anyLong())).thenReturn(true);

    assertThatCode(() -> service.iniciarRegistro(new IniciarRegistroRequest("ocupado@test.com")))
            .as("un email ya registrado no puede distinguirse de uno libre")
            .doesNotThrowAnyException();

    // Y no se emite el token de verificación de una cuenta que ya existe.
    verify(tokenVerificacionEmailRepository, never()).save(any());
}
```

---

## M-02 — Colisión de nombre de empleado deja el login de mostrador inutilizable de forma permanente

> **✅ CORREGIDO (2026-09-04).** El finder del login pasó a
> `findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue` y se eliminó el chequeo
> posterior de `isActive`, ahora redundante. Reproducido antes del fix con
> `AuthServiceEmpleadoHomonimoTest` (`@DataJpaTest` + H2, dos filas "Juan" en el mismo
> establecimiento): fallaba con `IncorrectResultSizeDataAccessException: 2 results were
> returned`. Suite completa en verde (725 tests).
>
> **Residual: ✅ CERRADO (2026-09-05).** La carrera de `crearEmpleado` (check-then-act sin
> constraint de respaldo) se cerró con `V23__unico_empleado_activo_por_nombre.sql`: índice
> único parcial sobre `(establecimiento_id, lower(nombre))` limitado a `rol = 'EMPLOYEE' AND
> is_active`, más la traducción de la violación al mismo mensaje de negocio que el guard
> (`EmpleadoService` pasa de `save` a `saveAndFlush` + catch, mismo patrón que
> `AuthService.registerOwner`). La migración incluye un paso previo que repara datos ya
> duplicados —conserva el de id más alto y desactiva los anteriores— para que el índice no
> pueda abortar un deploy.
>
> **Sin verificar contra Postgres:** Docker no estaba disponible, así que el SQL de V23 no se
> ejecutó en ningún test. La traducción del error sí está cubierta por
> `EmpleadoServiceTest.crearEmpleado_CarreraConOtraAltaDelMismoNombre_...`.

* **Severidad:** Media (impacto en disponibilidad, no en confidencialidad ni integridad)
* **Archivos:** `EmpleadoService.java:70` vs `AuthService.java:147-149`

El alta valida unicidad **sólo entre empleados activos**; el login busca **sin filtrar por
activo**:

```java
// EmpleadoService.crearEmpleado — el guard
usuarioRepository.existsByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(...)
                                                                  // ↑ sólo activos

// AuthService.authenticateEmpleado — la lectura
usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRol(...)  // ← sin isActive
        .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));
```

`findBy...` devuelve `Optional<Usuario>`: con dos filas, Spring Data lanza
`IncorrectResultSizeDataAccessException`, que cae en el catch-all de
`GlobalExceptionHandler` → **500**.

### Vector

Secuencia enteramente dentro del flujo normal del producto:

1. `POST /api/v1/establecimientos/7/empleados` → `{"nombre":"Juan","pin":"4816"}`
2. `DELETE /api/v1/establecimientos/7/empleados/{id}` → `desactivarEmpleado` pone `isActive=false` (no borra la fila)
3. Juan vuelve a trabajar. El dueño lo da de alta otra vez: `{"nombre":"Juan","pin":"9137"}` → **pasa**, porque el `existsBy...AndIsActiveTrue` no ve al desactivado.
4. Juan intenta entrar en el mostrador:

```http
POST /api/v1/auth/empleados/login
Cookie: saque_caja_device=<token válido>

{"nombre":"Juan","pin":"9137"}
```
→ `500 {"error":"Ocurrió un error interno..."}` — **siempre**, con PIN correcto o incorrecto.

El nombre "Juan" queda quemado para ese establecimiento. No hay endpoint de reactivación ni
de renombrado, así que el dueño **no puede arreglarlo desde la API**: hace falta tocar la base.

No es una escalada de privilegios (nadie entra sin PIN), pero rompe de forma permanente la
operación del mostrador, que es el camino crítico del producto.

### Fix propuesto

Alinear las dos consultas. La lectura del login ya descarta inactivos tres líneas más abajo
(`if (!Boolean.TRUE.equals(empleado.getIsActive()))`), así que llevar el filtro a la query es
además más simple que lo que hay:

```java
// UsuarioRepository
Optional<Usuario> findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(
        Long establecimientoId, String nombre, Role rol);
```

```java
// AuthService.authenticateEmpleado
Usuario empleado = usuarioRepository
        .findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(
                establecimientoIdDispositivo, nombre, Role.EMPLOYEE)
        .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));
// El chequeo de isActive de más abajo queda redundante y se borra.
```

Con esto la unicidad del alta y la resolución del login pasan a mirar exactamente el mismo
conjunto (activos del establecimiento), que es el invariante que el alta ya asume.

### Test que demuestra la vulnerabilidad

```java
// src/test/java/.../auth/service/AuthServiceTest.java
@Test
void loginEmpleado_conUnHomonimoDesactivado_resuelveAlEmpleadoActivo() {
    // Falla HOY: el repo devuelve 2 filas y Spring Data lanza
    // IncorrectResultSizeDataAccessException antes de llegar a autenticar.
    Usuario vigente = empleadoDe(7L, "juan", true);   // activo, PIN 9137
    when(usuarioRepository.findByEstablecimientoIdAndNombreIgnoreCaseAndRolAndIsActiveTrue(
            7L, "juan", Role.EMPLOYEE)).thenReturn(Optional.of(vigente));

    AuthResponse resp = authService.authenticateEmpleado(
            new EmpleadoLoginRequest("Juan", "9137", null), 7L);

    assertThat(resp.token()).isNotBlank();
}

@Test
void crearEmpleado_rechazaNombreDuplicadoAunqueElHomonimoEsteDesactivado() {
    // Guard de regresión del otro lado del invariante.
}
```

---

# BAJA

## B-01 — `actualizarEstablecimiento` evalúa el plan del ACTOR, no el del DUEÑO

> **❌ DESCARTADO (2026-09-05) — decisión de producto.** Que un ADMIN pueda decidir si el
> complejo exige seña, con independencia del plan del dueño, es el comportamiento buscado:
> el admin es soporte y tiene que poder destrabar un caso puntual. El código queda como está.

* **Archivo:** `EstablecimientoService.java:96`

```java
establecimiento.setRequiereSena(esPlanLimitado(usuarioAutenticado.getPlanSuscripcion()) || request.requiereSena());
//                                             ↑ el que ejecuta, no el titular
```

`crearEstablecimiento:56` lo hace bien (`esPlanLimitado(dueno.getPlanSuscripcion())`).

**Vector:** `validarPropietarioOAdmin` admite ADMIN, cuyo plan no es `FREE`. Un ADMIN que
edite el complejo de un dueño FREE hace `PUT /api/v1/establecimientos/{id}` con
`"requiereSena": false` y la seña obligatoria del plan FREE se desactiva, saltándose la
restricción comercial. Requiere ADMIN, por eso es baja.

**Fix:** leer el plan del titular, que ya está cargado en la entidad:

```java
establecimiento.setRequiereSena(
        esPlanLimitado(establecimiento.getDueno().getPlanSuscripcion()) || request.requiereSena());
```

**Test:** `actualizarComoAdmin_noDesactivaLaSenaObligatoriaDeUnDuenoFree()` — hoy falla
porque `requiereSena` queda en `false`.

---

## B-02 — La idempotencia es opt-in y su matcheo de rutas es sorteable

> **✅ CORREGIDO (2026-09-05).** Los dos huecos cerrados:
> 1. **Opt-in → obligatoria** en las 4 rutas que mueven plata (`RUTAS_CLAVE_OBLIGATORIA`):
>    sin header, 400 y no se ejecuta nada. La subida de fotos queda opcional a propósito
>    (repetirla deja un archivo de más, no un cobro de más).
> 2. **Matcheo por igualdad de string → PathPattern sobre PathContainer** para TODAS las
>    rutas. `getRequestURI()` no decodifica y el HandlerMapping sí, así que
>    `/api/v1/%62uffet/ventas` esquivaba el filtro y llegaba igual al controller.
>
> Cubierto por 3 tests nuevos en `IdempotencyFilterTest` (los dos de plata fallaban con
> `expected: <400> but was: <200>` antes del fix). **Sin ruptura del front:** los 3 POST que
> hoy consume ya mandaban la clave; `/reservas/semanal` no se llama todavía.

* **Archivo:** `IdempotencyFilter.java:106-110, 180-190`

Dos huecos, ambos de bajo impacto real:

1. **Opt-in:** sin header `Idempotency-Key` el filtro no interviene. `POST /api/v1/buffet/ventas` repetido dos veces genera dos ventas, dos descuentos de stock y dos ingresos de caja.
2. **Matcheo por igualdad sobre la URI cruda:** `RUTAS_PROTEGIDAS.contains(request.getRequestURI())`. `getRequestURI()` devuelve la URI **sin decodificar**, mientras que el `HandlerMapping` de Spring sí decodifica los segmentos. `POST /api/v1/%62uffet/ventas` no matchea el set pero llega igual al controller, salteando el filtro incluso mandando la key.

**Por qué es baja:** el actor de esos endpoints ya está autenticado y autorizado sobre su
propio establecimiento; puede duplicar la venta simplemente llamando dos veces. No hay
frontera de seguridad cruzada — es robustez de negocio, no control de acceso. Anotado porque
el brief lo pedía explícitamente.

**Fix:** para (2), matchear sobre la ruta ya normalizada
(`ServletRequestPathUtils.parseAndCache(request)` / `PathPattern` sobre el path decodificado)
en vez de sobre `getRequestURI()`. Para (1), si se quiere garantía real, exigir la key
(`400` si falta) en las rutas que mueven plata, en vez de dejarla opcional.

---

## B-03 — El webhook de Resend no deduplica `svix-id`: replay dentro de la ventana de 5 minutos

> **⏸️ POSTERGADO (2026-09-05) — a implementar junto con el tracking, no antes.** Verificado:
> nadie consume el webhook fuera del controller que loguea. Como el handler no escribe estado,
> deduplicar hoy no evita ningún efecto — sería una tabla y un job de limpieza manteniéndose
> solos, para nada. **Condición de reactivación:** en cuanto el handler haga algo con el evento
> (registrar rebotes, marcar emails como no entregables), el dedup entra en el mismo cambio.

* **Archivo:** `ResendWebhookSignatureVerifier.java:40-52`

La verificación de firma está **bien hecha**: es obligatoria (no hay bypass), usa HMAC-SHA256
sobre `id.timestamp.payload`, compara en tiempo constante con `MessageDigest.isEqual`, y sin
`RESEND_WEBHOOK_SECRET` el endpoint queda cerrado en vez de abierto. La tolerancia de
timestamp es ±5 min.

Lo que falta es persistir los `svix-id` ya vistos: cualquiera que capture un webhook legítimo
puede reenviarlo tal cual durante 5 minutos y volverá a validar.

**Impacto hoy: ninguno.** El handler sólo hace `log.info(...)` — no escribe estado. Queda
anotado porque en cuanto se implemente el tracking real de bounces/opens que el propio
Javadoc anticipa, el replay pasa a tener efecto.

**Fix (cuando el handler haga algo):** tabla `webhook_evento_procesado(svix_id PK,
fecha_recepcion)` con constraint único, escrita antes de procesar; si el insert falla por
duplicado, responder `200` sin reprocesar. Es el mismo patrón que ya usa
`SolicitudIdempotente`, más un job de limpieza como `IdempotencyCleanupService`.

---

## B-04 — Oráculo de temporización en `/auth/password/recuperar`

> **❌ DESCARTADO (2026-09-05) — decisión de producto.** Coherente con M-01: si la UX del
> registro ya expone qué emails tienen cuenta, un canal lateral por temporización no protege
> nada que no esté expuesto por la puerta del frente.

* **Archivo:** `RecuperacionPasswordService.java:88-109`

La respuesta es correctamente indistinguible (siempre `200`), pero el trabajo no lo es:

* Email inexistente → un `SELECT` y `return`.
* Email existente → `DELETE` + 2 × SHA-256 + `INSERT` + `publishEvent`.

Medible con suficientes muestras, sobre todo porque el `INSERT` compite por el pool de Hikari
(`maximum-pool-size=5` en prod).

**Fix:** hacer el trabajo indistinguible, o —más simple y suficiente acá— mover todo el bloque
posterior al lookup a un `@Async` y retornar de inmediato en ambos caminos. El repo ya tiene
el executor configurado en `AsyncConfig`.

---

## B-05 — Cookie de dispositivo de caja con `SameSite=None` y CSRF deshabilitado

> **❌ DESCARTADO (2026-09-05) — no se toca.** No es explotable hoy y la configuración actual
> es la correcta mientras front y back vivan en dominios distintos. **Condición de revisión:**
> si alguna vez la sesión de usuario se muda de header a cookie, la premisa que hace seguro el
> `csrf.disable()` deja de valer y hay que revisar esto de nuevo.

* **Archivos:** `DispositivoCajaGate.java:57-66` y `SecurityConfig.java:57`

`csrf.disable()` es **correcto** para el grueso de la API: el JWT viaja en el header
`Authorization`, que un sitio de terceros no puede fijar. La excepción es la cookie
`saque_caja_device`, que sí es ambiental y se emite con `SameSite=None`, o sea que el
navegador la manda en requests cross-site.

**Por qué no es explotable hoy:** los dos endpoints que dependen de ella exigen algo más que
la cookie — `/auth/empleados/login` pide el PIN en un body `application/json` (que un `<form>`
cross-site no puede producir) y `/empleados/activos` es un `GET` cuya respuesta CORS no es
legible desde otro origen. Defensa en profundidad, no un agujero.

**Fix:** cuando front y back queden bajo el mismo dominio (el propio comentario del código lo
anticipa), pasar a `SameSite=Lax`. Mientras tanto, alcanza con dejar constancia de que
`csrf.disable()` es seguro *porque* el JWT no es una cookie, y que esa premisa deja de valer
si alguna vez se mueve la sesión a cookies.

---

## B-06 — Rate limiting en memoria: se diluye al escalar a más de una instancia

> **⏸️ POSTERGADO (2026-09-05) — se resuelve al escalar.** Con una sola instancia el límite
> es correcto. **Condición de reactivación: la segunda instancia.** Mover los baldes a un
> almacén compartido tiene que salir en el MISMO deploy que agregue la segunda instancia, no
> después: el día que haya N instancias, el presupuesto de intentos contra el PIN de 4 dígitos
> se multiplica por N. Ya está documentado en el código (`RateLimiterService:9-14` y
> `RateLimitFilter:25-31`), que es donde se va a leer llegado el momento.

* **Archivo:** `RateLimiterService.java:21`

`ConcurrentHashMap` por proceso. Es la única defensa contra fuerza bruta del PIN de 4 dígitos
del mostrador (`LOGIN_EMPLEADO_INTENTOS_MAXIMOS = 5` por ventana de 5 min).

Con una sola instancia el cálculo cierra: 10.000 combinaciones a 60 intentos/hora ≈ 7 días, y
`PINES_PROHIBIDOS` saca del espacio los triviales. Con N instancias detrás de un balanceador
el techo efectivo se multiplica por N, y el tiempo para barrer el espacio del PIN cae en la
misma proporción.

**El código ya lo documenta** (`RateLimiterService:9-14`, `RateLimitFilter:25-31`). Lo anoto
porque es la precondición de seguridad a revisar **antes** de escalar horizontalmente, no después.

**Fix (al escalar):** mover los baldes a Redis, y recién ahí reincorporar `X-Forwarded-For`
—confiando en él sólo cuando la request venga del proxy conocido, como advierte el comentario
de `RateLimitFilter`.

---

## B-07 — `crearReservaSemanal`: un mail por ocurrencia, sin consolidar, sobre un período sin tope

> **✅ CORREGIDO (2026-09-04).** Se implementó el fan-in con `TurnoFijoCreadoEvent`: un turno
> fijo publica ahora **un** evento con todos los ids y manda **2 emails** en **1 tarea `@Async`**,
> en vez de 2N emails en N tareas. Detalle al final de esta sección.
>
> **Residual del período sin tope: ✅ CERRADO (2026-09-05).** `crearReservaSemanal` ahora
> rechaza una fecha de fin posterior al 31/12 del año en que arranca el turno fijo
> (`validarPeriodoDentroDelAnio`), con un mensaje que dice hasta qué fecha se puede cargar.
> **Pendiente en el front (`saque-front`):** limitar el datepicker al 31/12 y aclarar en la
> pantalla que el turno fijo se carga por año calendario y se renueva.

* **Archivos:** `ReservaService.java:358` (publicación) y `ReservaNotificacionListener.java:51-73` (envío)

Residual del análisis de M-03, que quedó fuera del alcance de la decisión de producto: el
mecanismo de creación es deliberado, pero el de notificación no fue dimensionado para él.

```java
// ReservaService.crearReservaSemanal — un evento por ocurrencia
reservasGuardadas.forEach(r -> eventPublisher.publishEvent(new ReservaConfirmadaEvent(r.getId())));
```

Cada `ReservaConfirmadaEvent` dispara **una** ejecución de
`ReservaNotificacionListener.enviarNotificacionesConfirmacion`, que renderiza la plantilla
`reserva-confirmada` con el modelo de **una sola** reserva (una `fecha`, un `horaInicio`, un
`horaFin`) y manda **dos** emails: uno al jugador y otro al dueño. No hay plantilla ni camino
que consolide las N ocurrencias en un solo aviso.

Tres cosas se combinan mal:

1. **El período no tiene tope.** La única validación es `fechaInicioPeriodo <= fechaFinPeriodo` (`ReservaService:270`), y `generarFechasDelPeriodo` itera de a una semana hasta el final. `validarLimiteDeAnticipacion` (los 31 días de `crearReserva`) **no** se aplica en este camino. Un `fechaFinPeriodo` a 5 años son ~260 reservas → 260 mails al jugador y 260 al dueño.
2. **Cancelar no repara: duplica.** `cancelarReserva` publica `ReservaCanceladaEvent`, y cuando el que cancela **no** es el jugador (`ReservaNotificacionListener:100-109`) le manda otro mail. Deshacer un turno fijo de 52 ocurrencias son 52 mails más al jugador.
3. **El executor `@Async` es compartido y acotado.** `AsyncConfig` define `corePoolSize=2`, `maxPoolSize=5`, `queueCapacity=50`, sin `rejectedExecutionHandler` propio — o sea el `AbortPolicy` por defecto de `ThreadPoolExecutor`. Con cola acotada el pool sólo crece más allá del core cuando la cola se llena, así que el techo práctico ronda las ~55 tareas pendientes. Un turno fijo anual (52 ocurrencias) queda justo en el borde; uno de dos años lo pasa y las tareas excedentes se rechazan. Las reservas ya commitearon, así que el resultado es una notificación parcial silenciosa. Y como el executor es el mismo que usan verificación de email, recuperación de contraseña y el broadcast de marketing, la ráfaga también los encola detrás.

Impacto: entregabilidad y reputación del dominio remitente —justo lo que
`ReservaNotificacionListener.puedeNotificar` se preocupa por cuidar contra los bounces—, más
la experiencia del jugador, que recibe decenas de mails idénticos salvo la fecha.

### Fix propuesto

Consolidar el turno fijo en un aviso único, que además es lo que el usuario espera recibir:

```java
// ReservaService.crearReservaSemanal — en vez del forEach de eventos individuales
eventPublisher.publishEvent(new TurnoFijoCreadoEvent(
        reservasGuardadas.stream().map(Reserva::getId).toList()));
```

con un listener nuevo que renderice una plantilla `turno-fijo-confirmado` con la lista de
fechas (una tarea `@Async`, dos emails, en vez de N tareas y 2N emails). El
`ReservaConfirmadaEvent` por reserva se mantiene tal cual para `crearReservaManual` y
`confirmarReserva`, que sí son de a una.

Aparte, y con independencia del fix de mails, conviene acotar el período: un tope de
ocurrencias (por ejemplo 53, un año) rechazado con `IllegalArgumentException` evita de paso
que una sola request abra una transacción con cientos de inserts bajo el lock pesimista de la
cancha.

### Test

```java
@Test
void reservaSemanal_publicaUnUnicoEventoConsolidado() {
    // Falla HOY: se publican 52 ReservaConfirmadaEvent, uno por ocurrencia.
    reservaService.crearReservaSemanal(requestDeUnAnio(), OWNER_EMAIL);

    verify(eventPublisher, times(1)).publishEvent(any(TurnoFijoCreadoEvent.class));
    verify(eventPublisher, never()).publishEvent(any(ReservaConfirmadaEvent.class));
}
```

---

# INFORMATIVA

## I-01 — `usuario.setIsActive(true)` dentro del flujo de verificación de teléfono

* **Archivo:** `UsuarioService.java:112`

`verificarCodigo` reactiva la cuenta como efecto secundario. `isActive` está sobrecargado con
tres significados distintos, según documenta el propio modelo (`Usuario.java:104-110`):
onboarding incompleto de PLAYER, desactivación de EMPLOYEE, y habilitación de sesión
(`UsuarioUserDetailsMapper` lo mapea a `enabled`).

**No es explotable hoy** y verifiqué el camino completo: un empleado desactivado no puede
llegar a ese endpoint, porque `JwtAuthenticationFilter:67` exige `userDetails.isEnabled()`
para poblar el `SecurityContext`, y `DaoAuthenticationProvider` rechaza el login. Falla
cerrado por partida doble.

Queda como footgun: cualquier endpoint futuro que llame a `verificarCodigo` desde un contexto
menos restringido reactiva una cuenta desactivada. Lo correcto sería separar
`onboardingCompletado` de `isActive`, o acotar la reactivación a `rol == PLAYER`.

---

# Superficies revisadas SIN hallazgos

Detallo lo que verifiqué y salió limpio, porque la ausencia de hallazgos también es información.

### Aislamiento multi-tenant / IDOR — limpio

Recorrí **los 27 controllers**. Todo endpoint scopeado por establecimiento resuelve la entidad
y llama a `AutorizacionEmpleadoService` (`validarAccion` / `validarLectura` /
`validarPropietarioOAdmin`) **antes** de leer o escribir. Ninguna excepción.

Los sub-recursos verifican pertenencia antes de operar, con uno de dos patrones consistentes:

* Query acotada: `GastoRepository.findByIdAndEstablecimientoId`, `TurnoCajaRepository.findByIdAndEstablecimientoId`, `DispositivoCajaRepository.findByIdAndEstablecimientoId`.
* Chequeo explícito post-carga: `CanchaService:113`, `ProductoBuffetService:133`, `DiaNoLaborableService:53`, `BloqueoCanchaService:148-151`, `VentaService:213 y 227`.

Casos que parecían IDOR y **están mitigados** por código que no salta a la vista:

* **`GET /establecimientos/{id}/clientes/{jugadorId}`** — parecía permitir leer PII (email + teléfono) de cualquier usuario iterando `jugadorId`. Está cerrado en `ClienteService.java:125-127`: `existsByJugador_IdAndCancha_Establecimiento_Id` exige que el jugador tenga reservas **en ese** establecimiento antes de cargarlo. Falso positivo.
* **`DELETE /establecimientos/{id}/fotos/{fileId}`** — `FotoEstablecimientoService.buscarFoto` busca el `fileId` **dentro de la colección del establecimiento ya autorizado**, así que un `fileId` ajeno da 404, no borrado cruzado.
* **`PUT /feedback/{feedbackId}/destacar`** — deriva el establecimiento desde el propio feedback (`feedback.getReserva().getCancha().getEstablecimiento()`) y recién entonces autoriza. Imposible destacar un comentario ajeno.
* **`PUT /reservas/{id}/mover-cancha`** — valida explícitamente que la cancha destino sea del mismo establecimiento (`ReservaService:878-880`).

Ningún servicio confía en un `establecimientoId` del body sin autorizarlo: los dos que lo
reciben así (`VentaService.registrarVenta`, `VentaMetricasService`) lo pasan por
`validarAccion`/`validarPropietarioOAdmin` inmediatamente, y `AuthService.authenticateEmpleado`
directamente **descarta** el `establecimientoId` del body en favor del derivado de la cookie de
dispositivo (`AuthService:134-136`).

### Autorización y permisos — limpio

* Ningún endpoint sin `@PreAuthorize` queda sin control: los verifiqué uno por uno. Los que no la tienen validan a mano en el service (`UsuarioEliminacionService:89-91` y `OfertaMarketingService:29-31` exigen `Role.ADMIN`) o dependen de un secreto (`DispositivoCajaGate`, firma Svix, token de baja).
* `/api/v1/admin/**` exige ADMIN de verdad, verificado en ambos servicios.
* **El JWT no lleva el rol.** Los únicos claims son `sub`, `iat`, `exp`, `tokenVersion` y (para mostrador) `empleadoId` informativo. Las authorities se recargan de la base en cada request vía `userDetailsService.loadUserByUsername` (`JwtAuthenticationFilter:58`).
* **`tokenVersion` funciona.** Verifiqué la comparación de `JwtService:93-94`: `claims.get(..., Integer.class)` contra `principal.getTokenVersion()`, que es un `int` primitivo (`UsuarioPrincipal:20`) — hay unboxing y comparación numérica, **no** comparación de referencias. No cae en la trampa del caché de `Integer` para valores > 127. Se incrementa en logout, reset de contraseña y cambio de PIN.
* Las acciones de dueño no son alcanzables con EMPLOYEE: `revertirAusencia`, `moverReservaDeCancha`, `crearReservaSemanal`, `cancelarVenta`, gastos, historial de caja y reportes usan `validarPropietarioOAdmin`, que **no** tiene rama para empleados.
* La separación `marcarAusente` (EMPLOYEE con permiso) vs `revertirAusencia` (sólo dueño) está bien implementada.

### Manipulación de plata — limpio

* **Ningún DTO de entrada acepta un precio.** Verificado en `ReservaRequest`, `ReservaManualRequest`, `ReservaSemanalRequest` y `VentaRequest`: sólo ids, fechas y cantidades. Todo monto sale de `PrecioReservaCalculator` (server-side) o de `producto.getPrecio()` leído de la base.
* La seña de la reserva manual usa `cancha.getMontoSena()` del servidor; el cliente sólo manda el booleano `senaFisicaRecibida`.
* **`BigDecimal` en todo el camino del dinero.** Grep dirigido de `double`/`float` en identificadores de monto/precio/total/seña/saldo/subtotal: **cero resultados**. Los `Double` que existen son geo (`latitud`/`longitud`/`distanciaKm`) y `promedioCalificacion`.
* `PrecioReservaCalculator:48` documenta y corrige la normalización de escala de `BigDecimal.multiply` con un `setScale(2, HALF_UP)` explícito.
* Todos los DTOs de plata están validados: `@DecimalMin(inclusive=false)` en `GastoRequest.monto` y `MovimientoManualRequest.monto`, `@Min(1)` en `DetalleVentaRequest.cantidad`, `@DecimalMin(0.0)` en fondo inicial y saldo contado.
* `TurnoCajaService.registrarMovimiento:124` rechaza cualquier monto `<= 0` en el punto de entrada único de la caja.
* **Falso positivo mitigado:** `CanchaRequest.preciosPorDuracion` es un `Map<Integer, BigDecimal>` sin validación de valores, así que un dueño podría configurar un precio negativo. Lo frena la base: `V12__checks_montos.sql:59` (`chk_reservas_precio_no_negativo CHECK (precio_total >= 0)`) convierte el intento en un 409 vía `handleDataIntegrityViolation`. Vale igual agregar `@PositiveOrZero` en el DTO para que el error salga como 400 con mensaje útil, pero el invariante de dinero está protegido.

### Lógica de reservas y concurrencia — limpio

* **Doble-booking cerrado.** Los 4 caminos de escritura (`crearReserva`, `crearReservaManual`, `crearReservaSemanal`, `moverReservaDeCancha`) llaman a `bloquearCanchasRelacionadas` **antes** de `findSuperpuestas` y de persistir.
* **El caso de canchas compuestas que el constraint V10 no cubre está correctamente cubierto por el lock.** Lo verifiqué a mano: `PoolCanchaCalculator.canchasRelacionadas` incluye la cancha pedida **más toda cancha lógica/física que comparta pool con ella**, con el mismo criterio `afectaEstePool` que usa `hayDisponibilidad`. Reservar la lógica L={F1,F2} bloquea `{L,F1,F2}`; reservar F1 también bloquea `{F1,L,F2}` — se excluyen mutuamente. El orden ascendente por id (`.sorted()` en el service **y** `ORDER BY c.id ASC` en la query) evita deadlocks. La limitación de V10 está documentada en la propia migración y el lock la compensa.
* **La matriz de transiciones está completa.** `marcarAusente` exige `CONFIRMADA` **y** que el turno ya haya empezado; `finalizarReserva` rechaza `PENDIENTE_SENA`, `AUSENTE`, `CANCELADA` y `CANCELADA_PRERESERVA`; `cancelarReserva` rechaza `FINALIZADA` y `AUSENTE` (con el comentario que explica que sin eso el propio ausente borraba su no-show) y preserva la distinción `CANCELADA` / `CANCELADA_PRERESERVA`; `confirmarReserva` sólo acepta `PENDIENTE_SENA` no vencida. No encontré ninguna transición saltéable.

### Autenticación — limpio (salvo M-01 y M-02)

* **Todos los tokens se persisten hasheados.** Verificado en los cuatro flujos: verificación de email, recuperación de contraseña, OTP de teléfono (`TokenHasher.sha256Hex`) y token/código de dispositivo de caja (`DispositivoCajaService.hash`). Ningún valor crudo llega a la base; sólo viaja en el mail o el link.
* **El reset de contraseña es de un solo uso e invalida sesiones:** `RecuperacionPasswordService:132` incrementa `tokenVersion` y `:135` borra la fila del token.
* Los códigos de 6 dígitos tienen contador de intentos con destrucción del token a los 5 fallos, en los tres flujos que los usan.
* **El email sintético del empleado no es adivinable ni colisiona:** `empleado-<UUID.randomUUID()>@empleados.sacaladelangulo.interno` (`EmpleadoService:169`), sobre un dominio interno que no resuelve.
* La recuperación de contraseña **no** enumera cuentas (siempre 200) — el problema es sólo el registro (M-01).
* `jwt.secret` valida ≥256 bits en el arranque y falla rápido; sin default en las properties.

### Zona pública / PII — limpio

* Los tres DTOs públicos (`ComplejoCardResponse`, `ComplejoDetalleResponse`, `CanchaPublicaDto`) son records con campos explícitos. **No exponen `duenoId`, emails, teléfonos ni ids internos de usuario.** No hay serialización de entidades JPA en la zona pública, así que no hay riesgo de filtrado por campos de más.
* **La disponibilidad no filtra PII de jugadores.** `SlotDisponibleResponse` es `(inicio, fin)` y nada más: la grilla publica huecos libres, nunca reservas ocupadas ni quién reservó.
* Detalle menor por diseño: `FeedbackDestacadoDto.jugadorNombre` expone el nombre del autor de la reseña destacada. Es lo esperable en un marketplace (una reseña firmada) y lo destaca el dueño de forma deliberada. Lo señalo para que sea una decisión consciente, no un hallazgo.
* `BloqueoCanchaService:184` oculta el `motivo` del bloqueo cuando el que consulta es PLAYER.

### Subida de archivos — limpio

* **`ValidadorFoto` determina el tipo por magic bytes, no por extensión ni `Content-Type`.** Valida firmas de JPEG, PNG y WebP, y en WebP chequea los dos bloques (`RIFF` en 0..3 **y** `WEBP` en 8..11), así que un WAV o un AVI —que también empiezan con `RIFF`— no pasan.
* El tamaño se valida sobre el contenido real (5 MB), con el límite de multipart de Spring por encima (6 MB) para que el error de negocio gane sobre el del contenedor.
* No hay asociación ni borrado cruzado: `subir` y `borrar` autorizan con `validarPropietarioOAdmin` sobre el establecimiento de la ruta, y `borrar` además exige que el `fileId` esté en la colección de **ese** establecimiento.

### Inyección y validación de entrada — limpio

* **Cero SQLi.** Grep exhaustivo: no hay ni una sola concatenación de strings en `@Query`, ni `createNativeQuery`, ni `createQuery`, ni `jdbcTemplate`, ni `Statement` en todo el `src/main`. Todo es JPQL con `@Param` o derivación de nombres de método.
* **Todos los `@RequestBody` llevan `@Valid`**, salvo tres justificados: dos DTOs opcionales de sólo-label en `DispositivoCajaController` y el `String payload` crudo del webhook, que **tiene** que llegar sin procesar para poder verificar el HMAC.
* La paginación está acotada a 100 en los listados que la aceptan (`capPageSize` en `ReservaService` y `ClienteService`), y `ClienteService.comparadorDeCampo` usa una allowlist de campos de ordenamiento con `default -> throw`, lo que cierra el ordenamiento arbitrario.
* Log injection: no aplica como hallazgo, y de todas formas no hay ningún log que vuelque secretos (grep dirigido de password/pin/token en sentencias de log: cero resultados).

### Exposición de configuración y secretos — limpio

* **No hay ningún secreto hardcodeado ni logueado.** Verificado con grep sobre patrones de asignación literal y sobre sentencias de log.
* **`.env` nunca fue commiteado:** `git ls-files` sólo devuelve `.env.example`, y `git log --all -- .env` está vacío. El `.gitignore` usa el patrón correcto (`.env*` con excepción `!.env.example`), que cubre backups tipo `.env.bak` y `.env.local`.
* Actuator expone **sólo** `health`, con `show-details=never`. El `permitAll` de `/actuator/health/**` está acotado a esa ruta; el resto cae bajo `anyRequest().authenticated()`.
* Swagger apagado en la configuración base (comportamiento seguro por defecto sin perfil activo) y reactivado sólo en `dev`.
* Prod fija `include-stacktrace=never`, `include-message=never`, `include-binding-errors=never`, `include-exception=false`, y `GlobalExceptionHandler` tiene catch-all que loguea el detalle y devuelve un mensaje genérico. `handleDataIntegrityViolation` no filtra nombres de constraints al cliente.
* **CORS no usa `*` en ningún lado** y el default falla cerrado (`localhost:5173,localhost:3000`), así que un prod que se olvide de setear `CORS_ALLOWED_ORIGINS` queda restrictivo, no permisivo. `allowCredentials(true)` es compatible porque los orígenes son explícitos.
* Sentry con `send-default-pii=false` explícito.

---

## Conteo por categoría

| Categoría | Crítica | Alta | Media | Baja | Info |
|---|---|---|---|---|---|
| Aislamiento multi-tenant / IDOR | 0 | 0 | 0 | 0 | 0 |
| Autorización y permisos | 0 | 0 | 0 | 1 (B-01) | 0 |
| Manipulación de plata | 0 | 0 | 0 | 0 | 0 |
| Lógica de reservas / concurrencia | 0 | 0 | 0 | 2 (B-02, B-07) | 0 |
| Autenticación | 0 | 0 | 2 (M-01, M-02) | 2 (B-04, B-06) | 1 (I-01) |
| Zona pública / PII | 0 | 0 | 0 | 0 | 0 |
| Subida de archivos | 0 | 0 | 0 | 0 | 0 |
| Inyección y validación | 0 | 0 | 0 | 0 | 0 |
| Configuración y secretos | 0 | 0 | 0 | 1 (B-05) | 0 |
| Webhooks | 0 | 0 | 0 | 1 (B-03) | 0 |
| **Total** | **0** | **0** | **2** | **7** | **1** |

## Orden de corrección sugerido

**Cerrado:** ~~B-07~~ y su residual · ~~M-02~~ y su residual (migración V23).
**Descartado por decisión de producto:** ~~M-01~~ (la UX ya expone lo mismo) · ~~B-01~~ (el admin debe poder overridear la seña).

**Cerrado:** ~~B-07~~ + residual · ~~M-02~~ + residual (V23) · ~~residual de M-01~~ (límite por IP).
**Descartado por decisión de producto:** ~~M-01~~ · ~~B-01~~ · ~~B-04~~ · ~~B-05~~.
**Postergado con condición de reactivación explícita:** B-03 (cuando el webhook haga algo) · B-06 (cuando haya una segunda instancia).

**Queda abierto:**

1. **I-01** — footgun latente, no explotable. Sin acción.
2. ~~**Front: pantalla de turno fijo**~~ — ✅ construida (2026-09-06). Botón "Turno fijo" en la agenda (sólo dueño: `/reservas/semanal` exige OWNER/ADMIN) → drawer con `FormTurnoFijo`. El datepicker topea en el 31/12 del año de inicio con `topeDelPeriodo`, que espeja `validarPeriodoDentroDelAnio`, y el endpoint manda `Idempotency-Key`. La aritmética vive en `src/lib/panel/turno-fijo.ts` con 10 tests.
3. **V23 se valida en el próximo arranque:** Flyway aplica la migración al levantar la app. Si el SQL tuviera un error, la app no arranca y se ve en dev antes de prod.
5. El resto (**B-02** a **B-06**, **I-01**) son defensa en profundidad: agendables, ninguno urgente. **B-06** tiene que resolverse **antes** de escalar a más de una instancia, no después.
