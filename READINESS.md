# READINESS — Auditoría de production-readiness (backend `sacaladelangulo`)

**Fecha:** 2026-08-05 (actualizado 2026-08-06 con verificación Docker) · **Stack:** Spring Boot 3.5.14, Java 21, Postgres, Flyway
**Target:** contenedor en Railway/Render/Fly.io · Postgres administrado · front en Vercel (`saque.ar`)

---

## 1. Resumen ejecutivo

**¿Está listo para deploy? — SÍ, con condiciones.** El *código de la aplicación* ya venía
notablemente endurecido para producción (ver §2.0 "Ya resuelto"). Casi todos los bloqueantes
clásicos que esta clase de auditoría suele encontrar **ya estaban resueltos** por el dueño:
`ddl-auto=validate` + Flyway con baseline, secretos sin default con fail-fast, actuator limitado a
`health`, CORS con orígenes explícitos (no `*`), executor `@Async` acotado, zona horaria fijada,
jobs/rate-limiter documentados como de instancia única.

Lo que **faltaba** era el andamiaje de contenedor/producción — que **se creó y validó** en esta
auditoría (Dockerfile, `application-prod.properties`, `.dockerignore`, `.env.example`). Con eso,
el arranque en perfil `prod` fue verificado localmente (log JSON estructurado a stdout, binding a
`PORT`, fail-fast de secretos).

> **⚠️ Actualización 2026-08-06 (verificación con Docker real):** correr los tests de integración
> con Testcontainers (Postgres real + Flyway real + `ddl-auto=validate`) destapó **un bloqueante
> que habría hecho fallar el PRIMER deploy contra una base nueva** (ver B0 en §2.1): la entidad
> `SolicitudIdempotente` mapeaba `cuerpo_respuesta` con `@Lob` (→ `oid` en Postgres) mientras que
> `V1__baseline.sql` crea la columna como `text` → `validate` aborta el arranque contra una DB
> creada por Flyway. **Ya está corregido y verificado.** No se había detectado antes porque el
> `V1__baseline.sql` nunca se había ejecutado contra una base real (la de dev fue creada por el
> viejo `ddl-auto=update`, que la dejó como `oid`). La imagen Docker también se buildeó y corrió OK.

### Los 3 puntos que quedan para vos antes de apretar "deploy"

1. **Cargar las env vars en la plataforma** (§5). Sin `JWT_SECRET` (≥32 bytes), `DB_*`,
   `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS` y las de Resend, la app no arranca o manda mails/links
   mal. Es acción de deploy, no de código.
2. ~~Buildear y verificar la imagen Docker una vez.~~ **HECHO (2026-08-06).** `docker build` OK
   (imagen `saque-back:latest`, 539 MB) y `docker run` de humo OK: arranca como **PID 1** (recibe
   SIGTERM → graceful shutdown), **usuario no root** (`appuser`), JVM container-aware, perfil `prod`,
   **log JSON a stdout**. Además se corrió la suite completa con Testcontainers (Postgres real):
   **verde** tras corregir B0.
3. **Health check — DECIDIDO: TCP/por puerto** (cero código). `/actuator/health` requiere auth, así
   que la plataforma chequea el **puerto/TCP**, no una URL, y no se toca `SecurityConfig.java`.
   Configurar según §5, Paso 3.

> **Decisiones confirmadas por el dueño (2026-08-05):** estrategia de migraciones **sin cambios**
> (se deja el Flyway + baseline actual); health check por **TCP**. No queda ninguna decisión abierta.

> **Constraint de arquitectura a respetar YA:** desplegar como **UNA sola instancia**. Los jobs
> `@Scheduled` y el rate limiter son en memoria/por-instancia; con 2+ réplicas se duplican los
> jobs y el rate limit se vuelve inconsistente (§2.2).

---

## 2. Hallazgos por prioridad

### 2.0 Ya resuelto en el código (verificado, no requiere acción)

Lo listo para que quede constancia de que se leyó el código y no se rehace lo que ya está bien:

| Área | Estado verificado |
|---|---|
| **Esquema/migraciones** | Flyway con `V1__baseline.sql` (25 tablas, baseline completo) + V2–V8. `ddl-auto=validate` en base **y** en `dev`. Ver §4. |
| **Fail-fast de secretos** | `${JWT_SECRET}` y `${DB_PASSWORD}` **sin default**. `JwtService` además valida largo ≥32 bytes en el constructor y **tira `IllegalStateException` al arrancar** si falta o es débil (es un `@Service`, se instancia siempre → la app no levanta sin un JWT válido). |
| **Secretos en git** | `.env` **nunca commiteado** (`.env*` en `.gitignore`, y el historial está limpio: los `whsec_` que aparecen son un prefijo constante y un secreto de test fabricado). |
| **Actuator** | `management.endpoints.web.exposure.include=health` + `show-details=never`. Nada de `env`/`heapdump`/`*`. |
| **CORS** | Orígenes explícitos por env (`app.cors.allowed-origins`), `allowCredentials(true)` con lista concreta — **no** `*` con credenciales. |
| **`@Async`** | `AsyncConfig` con `ThreadPoolTaskExecutor` acotado (core 2 / max 5 / queue 50) + handler de excepciones. **No** es el `SimpleAsyncTaskExecutor` sin límite. |
| **Zona horaria** | `TimeZone.setDefault("America/Argentina/Buenos_Aires")` en `main()` antes de `SpringApplication.run` → independiente de la TZ del contenedor. |
| **Ruido en prod** | `show-sql=false`, Swagger/springdoc **off** en base (solo `dev` los prende). |

### 2.1 BLOQUEANTE — sin esto no deploya (todos resueltos por archivos creados)

#### B0 — Schema mismatch `@Lob` vs `text` (BUG REAL, encontrado con Testcontainers) — CORREGIDO

> Este **sí era un bug de código**, no falta de scaffolding, y **habría hecho fallar el primer
> arranque contra una base de datos nueva** (el escenario exacto del deploy).

- **Qué pasaba:** `SolicitudIdempotente` mapeaba `cuerpo_respuesta` con `@Lob` sobre un `String`.
  En Postgres, `@Lob` en un `String` hace que Hibernate espere la columna como `oid` (large
  object / CLOB), pero `V1__baseline.sql` la crea como `text`. Con `ddl-auto=validate`, Hibernate
  aborta el arranque: *"wrong column type... found text, but expecting oid"*.
- **Por qué no se había visto:** el `V1__baseline.sql` **nunca se había ejecutado contra una base
  real**. La base de dev fue creada históricamente por `ddl-auto=update` (que, con `@Lob`, dejó la
  columna como `oid`), así que ahí `validate` pasaba. La primera vez que Flyway corrió V1 contra
  una base nueva fue en este test con Testcontainers — y saltó el mismo error que saltaría en el
  primer deploy a un Postgres administrado vacío.
- **Riesgo en prod:** la app **no arranca** contra la DB nueva de producción. Bloqueante total.
- **Fix aplicado (verificado):** en `SolicitudIdempotente` se reemplazó `@Lob` por
  `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`, que mapea a `text` (coincide con la migración y con la
  intención real: es un cuerpo de respuesta JSON cacheado, no un blob). Tras el fix, el contexto
  levanta y **Flyway V1–V8 + `validate` pasan contra un Postgres nuevo**. Único cambio en código
  de la app de toda esta auditoría; no toca lógica de negocio ni migraciones.
- **Acción para vos (dev local):** tu base de dev tiene la columna como `oid`; recreala/dropeá la
  columna `cuerpo_respuesta` (o recreá la DB de dev) para que `validate` pase también localmente.
  La tabla es cache efímera, no se pierde nada. Prod nueva no necesita nada (V1 ya crea `text`).

> Nota: los siguientes (B1–B5) eran bloqueantes por **ausencia de scaffolding**, no por bugs. Los
> archivos creados en §3 los cierran, y la imagen Docker **ya se buildeó y corrió OK** (§1, punto 2).

| # | Qué faltaba | Riesgo en prod | Resuelto por |
|---|---|---|---|
| B1 | **No había Dockerfile** | No hay forma de empaquetar/correr el contenedor. | `Dockerfile` (multi-stage, JRE 21 slim, non-root, layered, JVM container-aware). |
| B2 | **No había perfil de prod** | La config base sola no bindea `PORT`, no fuerza SSL a la DB, ni maneja el proxy. | `application-prod.properties`. |
| B3 | **No se leía `PORT`** | Railway/Render inyectan un puerto dinámico; la app escuchaba 8080 fijo → el proxy no la alcanza, deploy "unhealthy". | `server.port=${PORT:8080}` + `server.address=0.0.0.0`. **Verificado** en arranque local. |
| B4 | **Sin SSL a la DB** | Postgres administrado exige/espera TLS; conexión en claro o rechazada. | `...?sslmode=${DB_SSLMODE:require}` en el perfil prod. |
| B5 | **Sin proxy/forward-headers** | Los links de los mails saldrían como `http://…:8080` y las cookies `Secure` fallarían detrás del TLS de la plataforma. | `server.forward-headers-strategy=framework`. |

### 2.2 IMPORTANTE — deploya, pero hay que atenderlo

| # | Hallazgo | Riesgo concreto | Qué lo resuelve |
|---|---|---|---|
| I1 | **`/actuator/health` requiere auth** | El health check HTTP de la plataforma recibe `401` → la instancia nunca se marca "healthy" y el deploy puede quedar en loop de reinicio. | **DECIDIDO: health check TCP/por puerto** (Railway: dejar el path vacío; Render: sin health-check path; Fly: `[[services.tcp_checks]]`). Cero código. La alternativa de abrir `/actuator/health/**` con permitAll (§2.4) queda **descartada** por decisión del dueño; se documenta por si en el futuro se quieren probes HTTP reales. |
| I2 | ~~Imagen Docker sin buildear~~ **RESUELTO (2026-08-06)** | — | `docker build` OK (`saque-back:latest`, 539 MB) + `docker run` de humo OK (PID 1, non-root, JSON logs, perfil prod). |
| I3 | **Instancia única obligatoria** | 4 jobs `@Scheduled` (expiración de reservas c/1min, avisos fin de prueba, limpieza idempotencia, limpieza rate-limiter) **se disparan por instancia**: con 2+ réplicas corren doble (ej. doble email de aviso). El rate limiter es un `ConcurrentHashMap` en memoria: no se comparte ni sobrevive reinicios. | **Hoy:** desplegar con `replicas=1` / sin autoscaling. **Documentado** en el código y acá. **Si algún día escalás horizontalmente:** ShedLock (jobs) + Redis (rate limit). |
| I4 | **File logging heredado de la base** | La base escribe a `logs/sacaladelangulo.log`; en un contenedor el disco es efímero y solo se llena. | El perfil prod lo **apaga** (`logging.file.name=`) y manda **JSON ECS a stdout** (`logging.structured.format.console=ecs`). **Verificado**: 15 líneas JSON válidas en el arranque local. |
| I5 | **Sin apagado ordenado** | En cada redeploy, SIGTERM cortaría requests en vuelo y mails encolados a mitad. | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=25s` (perfil prod) + `ENTRYPOINT` en exec-form (java = PID 1, recibe la señal). |

### 2.3 NICE-TO-HAVE

| # | Hallazgo | Nota |
|---|---|---|
| N1 | **jjwt 0.11.5 desactualizado** | Ya anotado por el dueño en `JwtService` (B14). La 0.12.x cambia la API (`parserBuilder`→`parser`, etc.), no es un simple bump. No bloquea el deploy; agendarlo. |
| N2 | **`spring.jpa.open-in-view` = true (default)** | Mantiene la conexión de DB abierta durante el render de la respuesta → con un pool chico (5) puede presionar. **No se tocó**: apagarlo puede romper lazy-loading en el render. Evaluar con calma. |
| N3 | **Hikari sizing tentativo** | Se puso `maximum-pool-size=5` (env `DB_POOL_MAX_SIZE`). Ajustar según el límite real de conexiones del plan de DB elegido. Regla: `pool_max < (límite_del_plan / nº_instancias)`. |
| N4 | **Rate limiter/idempotencia se limpian por tiempo** | OK para instancia única. Si migrás a Redis (I3), esto se simplifica. |

### 2.4 Cambio propuesto para I1 (requiere tu OK — toca código de seguridad)

En `SecurityConfig.securityFilterChain`, **antes** de `.anyRequest().authenticated()`:

```java
.requestMatchers(org.springframework.http.HttpMethod.GET,
        "/actuator/health", "/actuator/health/**").permitAll()
```

Deja pasar **solo** el health (que con `show-details=never` devuelve `{"status":"UP"}` — sin
detalle interno). No lo apliqué porque es un `.java` de seguridad y tu regla es no tocar lógica
sin confirmar. Si preferís no tocarlo, andá con la Opción A (TCP) de I1.

---

## 3. Archivos creados

| Archivo | Qué hace |
|---|---|
| `Dockerfile` | Build multi-stage: stage Maven (JDK 21) que compila el jar y extrae sus capas; stage runtime `eclipse-temurin:21-jre-jammy`, usuario **non-root** (`appuser`), capas copiadas por separado para cachear, JVM **container-aware** (`-XX:MaxRAMPercentage=75`, sin `-Xmx` fijo), `SPRING_PROFILES_ACTIVE=prod` por default, `ENTRYPOINT` exec-form con `JarLauncher`. |
| `.dockerignore` | Achica el build context y **evita filtrar `.env`/secretos** y artefactos locales (`target/`, `logs/`, `.git/`, docs) a la imagen. |
| `src/main/resources/application-prod.properties` | Perfil prod: `PORT`/`0.0.0.0`, `forward-headers-strategy`, graceful shutdown, SSL + pool Hikari dimensionado + reintentos de arranque (Hikari/Flyway), probes readiness/liveness, errores sin stacktrace/mensaje al cliente, **log JSON (ECS) a stdout** y file-logging apagado. Solo override; hereda la base. |
| `.env.example` | Inventario **completo y documentado** de env vars (requeridas / requeridas-en-prod / opcionales) con defaults y notas. Para copiar a `.env` en dev y como referencia de qué cargar en la plataforma. |
| `.gitignore` (editado) | Se agregó `!.env.example` para que la plantilla **sí** se commitee (antes la tapaba `.env*`). |
| `READINESS.md` | Este reporte. |

### Archivos de código modificados (2026-08-06, tras la verificación con Docker)

| Archivo | Cambio | Tipo |
|---|---|---|
| `core/idempotencia/SolicitudIdempotente.java` | `@Lob` → `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` en `cuerpo_respuesta` (fix de B0). | Mapeo de persistencia (no lógica de negocio). |
| `support/AbstractPostgresIntegrationTest.java` | Contenedor Testcontainers **singleton** + limpieza (`TRUNCATE`) de tablas antes de cada método. | Solo test (infra de test). |
| `cierrecaja/service/TurnoCajaConcurrenciaIntegrationTest.java` | El perdedor de la apertura concurrente acepta `DataIntegrityViolationException` como pérdida válida de la carrera. | Solo test. |

**Nada de lógica de negocio fue modificado.** El único cambio en código de app (B0) es un mapeo de
persistencia que corrige un bug bloqueante; el resto son infra/config y tests. La propuesta de I1
(§2.4) sigue **sin aplicar** (se eligió health check TCP).

---

## 4. Plan de migraciones (a tu confirmación)

**Detección:** `ddl-auto=validate` en `application.properties` **y** en `application-dev.properties`.
El proyecto **ya usa Flyway** (no `update`/`create`): `db/migration/V1__baseline.sql` (baseline
completo, 25 `CREATE TABLE` hechos a mano leyendo cada `@Entity`) + `V2…V8` incrementales, con
`baseline-on-migrate=true` y `baseline-version=1`.

**Conclusión: no hay nada que migrar. La estrategia ya es la de producción.** No propongo ningún
cambio de esquema/estrategia. Cómo se comporta:

- **Base de prod NUEVA y vacía:** `baseline-on-migrate` **no** dispara (solo aplica a esquemas *no
  vacíos*). Flyway corre **V1→V8 completas** → esquema íntegro. ✔ **Ahora verificado de verdad**:
  con Testcontainers, V1–V8 se ejecutaron contra un Postgres nuevo y `ddl-auto=validate` pasa —
  esto es lo que destapó B0 (§2.1), que se corrigió. Antes solo se había verificado por lectura
  que V1 tuviera todas las tablas, no que los *tipos* de columna coincidieran con las entidades.
- **Base ya existente** (creada históricamente con `ddl-auto=update`): se marca baseline en V1 y se
  aplican V2→V8. ✔

**Único punto opcional a tu criterio (no lo cambio sin tu OK):** en un flujo *green-field* (prod
arranca de una base vacía y nunca hubo `ddl-auto=update`), `baseline-on-migrate=true` es inofensivo
pero innecesario; algunos prefieren dejarlo en `false` para que una base "sucia" falle ruidosa en
vez de baselinear en silencio. **Recomendación: dejarlo como está** — cubre los dos escenarios sin
riesgo. Solo tocá esto si querés el comportamiento estricto green-field.

> **CONFIRMADO (2026-08-05):** se deja la estrategia como está. No se aplicó ningún cambio de
> esquema ni de `baseline-on-migrate`. El modo estricto green-field queda descartado.

---

## 5. Checklist de deploy (Railway / Render / Fly)

### Paso 0 — Pre-requisitos locales (una vez)
- [x] `./mvnw clean package` compila y produce el jar ejecutable. ✔ (`target/sacaladelangulo-0.0.1-SNAPSHOT.jar`, layered).
- [x] `docker build -t saque-back .` construye la imagen. ✔ (`saque-back:latest`, 539 MB).
- [x] `docker run` de humo: arranca como PID 1, non-root, JSON logs, perfil prod. ✔
- [x] Suite completa con Testcontainers (Postgres real): `./mvnw test -Dsurefire.excludedGroups=` → **410 tests, 0 fallos** (tras corregir B0). ✔
- [ ] **Dev local:** recreá/dropeá la columna `cuerpo_respuesta` de tu DB de dev (hoy `oid`, ahora se espera `text` — ver B0). Cache efímera, sin pérdida real.
- [ ] `docker run` de humo con env vars + una DB de prueba: arranca, loguea JSON, corre Flyway. ⬜

### Paso 1 — Base de datos
- [ ] Crear el **Postgres administrado** (Railway Postgres / Render Postgres / Fly Postgres).
- [ ] Anotar host, puerto, db, usuario, password. Confirmar que exige **SSL** (default del perfil: `sslmode=require`).
- [ ] Verificar el **límite de conexiones** del plan y ajustar `DB_POOL_MAX_SIZE` si hace falta (default 5).

### Paso 2 — Cargar env vars en la plataforma
**Requeridas (la app no arranca sin ellas):**
- [ ] `JWT_SECRET` — ≥32 bytes, único por entorno. Generar: `openssl rand -base64 48`.
- [ ] `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
  *(o, alternativa: `SPRING_DATASOURCE_URL` con la JDBC URL completa incl. `?sslmode=require`, + `DB_USERNAME`/`DB_PASSWORD`).*

**Requeridas en prod (tienen default de dev que NO sirve en prod):**
- [ ] `SPRING_PROFILES_ACTIVE=prod` *(el Dockerfile ya lo trae por default; setearlo igual si no usás esa imagen).*
- [ ] `FRONTEND_URL=https://saque.ar`
- [ ] `CORS_ALLOWED_ORIGINS=https://saque.ar` *(y `https://www.saque.ar` si aplica). **Nunca** `*`.*
- [ ] `RESEND_ENABLED=true`
- [ ] `RESEND_API_KEY=…` *(requerida si `RESEND_ENABLED=true`)*
- [ ] `MAIL_FROM=SacaLaDelAngulo <no-reply@saque.ar>` *(dominio verificado en Resend)*

**Opcionales (setear solo para cambiar el default):**
- [ ] `RESEND_WEBHOOK_SECRET` *(para verificar la firma del webhook de Resend; sin ella el endpoint queda cerrado)*
- [ ] `DB_SSLMODE` (def. `require`), `DB_POOL_MAX_SIZE` (5), `DB_POOL_MIN_IDLE` (1), `FLYWAY_CONNECT_RETRIES` (10)
- [ ] `JWT_EXPIRATION_MILLIS`, `JWT_EMPLEADO_EXPIRATION_MILLIS`, `CAJA_DISPOSITIVO_EXPIRATION_MILLIS`, `CAJA_CODIGO_TTL_MILLIS`, `MARKETING_MAIL_FROM`

> **Nota:** ImageKit y MercadoPago **no están integrados** en el backend (MercadoPago solo existe
> como *etiqueta* de método de pago en el mostrador; ImageKit no aparece en el código). **No cargar
> env vars para ellos** hasta que se integren de verdad.

### Paso 3 — Configuración de servicio
- [ ] Deploy vía **Dockerfile** (Railway/Render/Fly lo detectan solo).
- [ ] **Réplicas = 1** (sin autoscaling horizontal — ver I3).
- [ ] **Health check:** **TCP/por puerto** (decisión confirmada). Railway: dejar el health-check path vacío. Render: no configurar health-check path (usa el binding del puerto). Fly: `[[services.tcp_checks]]`.
- [ ] RAM del plan: con `MaxRAMPercentage=75`, un tier de 512 MB deja ~384 MB de heap. Subir el plan o bajar el % si hay presión.

### Paso 4 — Primer deploy y verificación
- [ ] Ver en los logs (JSON) que **Flyway** aplicó V1→V8 (base nueva) o V2→V8 (base existente) sin error.
- [ ] La app arranca y queda "healthy".
- [ ] Smoke test: registro en 2 pasos → llega el mail (Resend real), links apuntan a `https://saque.ar`.
- [ ] Confirmar en un mail que el link es **https** con el dominio real (valida `forward-headers-strategy`).
- [ ] CORS: una request real desde `saque.ar` pasa; desde otro origen, no.

### Paso 5 — Post-deploy
- [ ] Configurar el **webhook de Resend** apuntando a `https://<backend>/api/v1/webhooks/resend` con su secret.
- [ ] Apuntar el frontend (Vercel) a la URL del backend.
- [ ] Backups automáticos de la DB (los da el plan administrado; confirmá que estén ON).

---

### Anexo — cómo se verificó esto (evidencia)

**Ronda 1 (2026-08-05, sin Docker):**
- `./mvnw clean package` → **exit 0**, jar de 70 MB, **layered** (`layertools list` → 4 capas).
- Arranque local en perfil `prod` (jar recién buildeado): perfil `prod` activo, **log JSON ECS a
  stdout** (15 líneas), binding a `PORT`/8080, y fail-fast contra DB inexistente (comportamiento
  esperado). El `JarLauncher` del `ENTRYPOINT` existe en el jar en la ruta exacta usada.
- Git history y `.env`: sin secretos commiteados.

**Ronda 2 (2026-08-06, con Docker real):**
- `docker build -t saque-back .` → **OK**, imagen `saque-back:latest` (539 MB), multi-stage +
  capas + non-root.
- `docker run` de humo: java es **PID 1** (recibe SIGTERM → graceful shutdown), corre como
  **`appuser`** (non-root), `JAVA_TOOL_OPTIONS` con `MaxRAMPercentage` aplicado, perfil `prod`,
  **log JSON ECS a stdout** dentro del contenedor.
- **Tests de integración con Postgres real (Testcontainers):** primera corrida destapó **B0**
  (schema `@Lob`/`text`). Tras el fix: `./mvnw test -Dsurefire.excludedGroups=` → **BUILD SUCCESS,
  410 tests, 0 fallos, 0 errores** (incluye los 5 tests con Postgres real: doble-booking,
  apertura concurrente de caja, rollback de venta fallida, compensación cross-turno, cierre doble).
- Se corrigieron además 2 problemas de **calidad de los tests** (no de producto) que impedían que
  la suite pasara con Docker: contención entre contenedores por-clase (→ contenedor singleton) y
  aislamiento entre métodos (→ `TRUNCATE` antes de cada método). Detalle en §3.
