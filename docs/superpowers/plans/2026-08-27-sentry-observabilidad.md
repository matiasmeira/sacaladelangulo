# Sentry para observabilidad de errores — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: usar superpowers:executing-plans para ejecutar este plan.
> Es una tarea única de configuración (no una secuencia de endpoints/features independientes). Los pasos
> usan checkbox (`- [ ]`).

**Goal:** que las excepciones no controladas y los `log.error(...)` reales del backend lleguen a Sentry,
sin gastar la cuota gratuita (5000 eventos/mes) en condiciones de negocio esperadas ni filtrar PII.

**Origen:** pedido externo con un spec de referencia (Spring Boot genérico, `pom.xml` en `backend/`,
`application-sentry.yml`, `logback-spring.xml`). Ese spec no coincide con este repo: acá no hay carpeta
`backend/` (el `pom.xml` está en la raíz), el paquete es `com.matiasmeira.sacaladelangulo` (no `ar.saque`),
no existen archivos `.yml` (todo es `.properties`) y no hay `logback-spring.xml`. Este plan reemplaza esas
rutas por las reales y corrige dos cosas del spec original que no son ciertas para la versión actual del
SDK (ver "Decisiones" abajo).

## Contexto y decisiones (no re-litigar)

- **Dependencia correcta: `sentry-spring-boot-starter-jakarta`, no `sentry-spring-boot-starter`.** Este
  proyecto es Spring Boot 3.5.14 (namespace `jakarta.*`). El artefacto `sentry-spring-boot-starter` a
  secas es la variante `javax.*` para Spring Boot 2; con Jakarta no resuelve. Versión actual: `8.54.0`
  (el spec original pedía `7.8.0`, desactualizado).
- **No hace falta `application-sentry.yml` ni `logback-spring.xml`.** El repo no usa YAML en ningún
  lado (todo `.properties`) y no tiene `logback-spring.xml` propio — el logging ya se configura por
  properties (`logging.level.*`, `logging.structured.format.console=ecs` en prod). El starter de Sentry,
  al detectar `sentry-logback` en el classpath, auto-configura el `SentryAppender` sobre el logger root
  sin tocar XML. La config va directo en `application.properties` / `application-prod.properties`, igual
  que el resto de la configuración de este proyecto.
- **`sentry.logging.minimum-event-level=error`, no `warn`.** El spec original no fijaba esta property
  (usaba una key que no existe, `logging.level`). El proyecto tiene **43 sitios `log.warn(...)`** contra
  **8 `log.error(...)`**. La mayoría de los `warn` son condiciones de negocio esperadas — el caso más claro
  es `GlobalExceptionHandler.handleDataIntegrityViolation`, que loguea en `warn` específicamente el
  solapamiento de reservas detectado por el constraint de exclusión (una carrera de concurrencia normal,
  ya comunicada como 409 al cliente) y reserva `error` para el resto de violaciones de integridad, que sí
  son bugs. Mandar los 43 sitios de `warn` como Issues de Sentry quemaría la cuota gratuita con ruido
  esperado. `error` capta el catch-all de `GlobalExceptionHandler.handleUnexpectedException` y los otros
  `log.error` reales; `minimum-breadcrumb-level=warn` conserva el contexto de los `warn` como breadcrumbs
  adjuntos a un evento, sin generar un Issue por cada uno.
- **DSN sin default vacío, no fail-fast.** A diferencia de `IMAGEKIT_PRIVATE_KEY` (que si falta en prod
  rompe una feature entera), Sentry sin DSN configurado es un estado válido: el SDK queda deshabilitado
  (no-op) y el resto de la app sigue andando. `sentry.dsn=${SENTRY_DSN:}` en la base, sin override
  obligatorio en `application-prod.properties`, para poder deployar antes de tener el proyecto de Sentry
  creado sin que la app deje de arrancar.
- **`sentry.environment` se deriva de `SPRING_PROFILES_ACTIVE`**, no de una variable nueva — coincide con
  el label que ya usa el resto del proyecto (`dev` / `prod`) y no requiere setear nada aparte en la
  plataforma.
- **No se agrega `SentryHealthCheck`.** El spec original lo listaba como opcional. En este repo un
  `HealthIndicator` que devuelve `Health.down()` cuando `Sentry.isEnabled()` es `false` rompería
  `/actuator/health` (estado agregado, sin scoping) en cualquier entorno sin `SENTRY_DSN` — incluido dev
  por default. Como "Sentry sin configurar" es un estado válido y esperado (ver punto anterior), ese
  health check reportaría un falso unhealthy. Se descarta en vez de escribir una versión que siempre
  devuelve `up()` sin chequear nada real.
- **No se manda `sentry.release`.** Requeriría exponer la versión de build (ej. goal `build-info` del
  `spring-boot-maven-plugin`, hoy no configurado) o inyectar el SHA de git desde la plataforma. Es una
  mejora real pero separada; no vale enganchar un cambio de build al mismo cambio que agrega captura de
  errores. Queda en "Fuera de alcance".
- **No se activa `sentry.send-default-pii`.** El spec de referencia no lo mencionaba, pero es el default
  recomendado en la guía genérica de Sentry y vale dejarlo explícito: esta API maneja JWT, teléfonos y
  emails de usuarios reales. Con `send-default-pii` en `true`, Sentry adjunta IP, cookies y headers de
  la request (incluido `Authorization`) a cada evento. Se deja en su default (`false`).
- **`traces-sample-rate=0.0` en la base, `0.1` solo en prod.** No tiene sentido pagar la cuota de
  performance monitoring en dev/test.

## Cambios

### Task 1: Dependencias de Sentry

**Archivos:**
- Modificar: `pom.xml`

- [ ] Agregar, después del bloque de `spring-boot-starter-actuator` (línea ~45), estas dos dependencias:

```xml
<!-- Sentry: captura de excepciones no controladas y de los log.error/log.warn (ver
     sentry.logging.* en application.properties) hacia Sentry.io. sentry-spring-boot-starter-jakarta
     es la variante para Spring Boot 3 (namespace jakarta.*); sentry-spring-boot-starter a secas es
     para Spring Boot 2 (javax.*) y no aplica acá. -->
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
    <version>8.54.0</version>
</dependency>
<!-- Con esto en el classpath, el starter de arriba auto-configura un SentryAppender sobre el
     logger root (ver sentry.logging.* más abajo) sin tocar la config de Logback. -->
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-logback</artifactId>
    <version>8.54.0</version>
</dependency>
```

- [ ] `./mvnw clean compile` — debe terminar en `BUILD SUCCESS`.

### Task 2: Configuración en application.properties (base)

**Archivos:**
- Modificar: `src/main/resources/application.properties`

- [ ] Agregar al final del archivo (después del bloque `CONFIGURACIÓN DE LOGS`):

```properties
# ===============================
# SENTRY (observabilidad de errores)
# ===============================
# Vacío por default: sin DSN el SDK queda deshabilitado (no-op) y el resto de la app
# sigue andando igual. Se define por env var, nunca hardcodeado (ver .env.example).
sentry.dsn=${SENTRY_DSN:}
# Mismo label que usa el resto del proyecto para distinguir entorno (dev/prod).
sentry.environment=${SPRING_PROFILES_ACTIVE:development}

# Solo ERROR se manda como Issue. Este proyecto tiene 43 sitios log.warn(...) que son
# condiciones de negocio esperadas (ej. GlobalExceptionHandler.handleDataIntegrityViolation
# logueando en warn el solapamiento de reservas ya comunicado como 409) contra 8 log.error(...)
# que sí son bugs reales, incluido el catch-all de GlobalExceptionHandler.handleUnexpectedException.
# Mandar los warn como Issues quemaría la cuota gratuita (5000 eventos/mes) con ruido esperado.
sentry.logging.minimum-event-level=error
# Los warn SÍ quedan como breadcrumbs (contexto adjunto a un evento), sin generar un Issue propio.
sentry.logging.minimum-breadcrumb-level=warn

# Apagado en la config base: sin traces-sample-rate>0 no se manda performance data en
# dev/test. application-prod.properties lo prende con una muestra chica.
sentry.traces-sample-rate=0.0

# Default explícito (no hace falta activarlo): esta API maneja JWT, teléfonos y emails
# reales. send-default-pii=true adjuntaría IP, cookies y headers (incluido Authorization)
# a cada evento.
sentry.send-default-pii=false
```

- [ ] `./mvnw clean compile` — debe seguir en `BUILD SUCCESS`.

### Task 3: Override de producción

**Archivos:**
- Modificar: `src/main/resources/application-prod.properties`

- [ ] Agregar una sección nueva, después del bloque `--- Logging estructurado a stdout ---`:

```properties
# --- Sentry -------------------------------------------------------------------
# environment ya sale de SPRING_PROFILES_ACTIVE=prod (ver application.properties) y no
# hace falta pisarlo acá. Solo se ajusta la muestra de performance monitoring: 10% de
# las requests normales (los errores se capturan siempre, ver sentry.logging.* en la base).
sentry.traces-sample-rate=0.1
```

### Task 4: Variable de entorno

**Archivos:**
- Modificar: `.env.example`

- [ ] Agregar, después del bloque `IMAGEKIT_PRIVATE_KEY` (última línea del archivo):

```properties

# --- Sentry (observabilidad) --------------------------------------------------
# [OPCIONAL] DSN del proyecto de Sentry. Sin ella, Sentry queda deshabilitado (no-op) y
# la app funciona igual. Se saca del dashboard de Sentry > Settings > Client Keys (DSN).
# La DSN no es secreta (es pública en clientes), pero igual va por env var y no hardcodeada.
# SENTRY_DSN=
```

- [ ] No tocar `.env` real (gitignoreado, fuera del repo versionado). Si el usuario ya tiene un
  proyecto de Sentry creado, agregar `SENTRY_DSN=<dsn real>` ahí a mano; si no, dejarlo vacío y seguir
  con la verificación (el SDK arranca igual, deshabilitado).

## Verificación manual

No hay tests automatizados que agregar: es configuración de arranque del SDK, no lógica de negocio. La
verificación es funcional, contra un DSN real.

- [ ] `./mvnw test` (suite normal) — debe seguir en la misma cantidad de tests en verde que antes del
  cambio. Confirma que agregar el starter no rompe el arranque del contexto de Spring en los tests
  (`spring.config.import=` en tests deja `SENTRY_DSN` sin resolver → cae al default vacío → SDK no-op).
- [ ] Conseguir un DSN real (crear un proyecto Java/Spring Boot en sentry.io si no existe uno) y
  setearlo en `.env` local: `SENTRY_DSN=https://...@....ingest.sentry.io/...`.
- [ ] `./mvnw spring-boot:run` con el perfil dev — debe arrancar sin errores.
- [ ] Agregar temporalmente, en cualquier controller existente (ej. un método nuevo en
  `DisponibilidadController`), un endpoint de prueba:

  ```java
  @GetMapping("/test-error")
  public void testError() {
      throw new RuntimeException("Error de prueba para Sentry");
  }
  ```

- [ ] `curl http://localhost:8080/api/v1/.../test-error` (con la ruta real del controller elegido).
- [ ] Confirmar en `https://sentry.io/organizations/<org>/issues/` que aparece el error, con
  stacktrace completo y `environment: dev`.
- [ ] **Borrar el endpoint de prueba antes de commitear** — no debe quedar en el diff final.
- [ ] Confirmar que un `log.warn(...)` normal (ej. forzar el 409 de solapamiento de reservas
  reservando dos veces la misma cancha/horario en paralelo) **no** genera un Issue nuevo en Sentry,
  solo queda como breadcrumb si después ocurre un evento real. Esto valida
  `sentry.logging.minimum-event-level=error` en vez de `warn`.

## Restricciones

- Commits en español, Conventional Commits, imperativo (`feat(observabilidad): ...` o similar). Sin
  palabras de relleno tipo IA (robusto, eficiente, optimizado, comprehensive, mejorado, potente,
  flexible, escalable, sólido).
- No commitear el endpoint `/test-error` ni ningún DSN real hardcodeado.
- No tocar `.env` (gitignoreado).

## Fuera de alcance

- **`sentry.release`** (tracking de versión de release): requiere exponer la versión de build
  (`build-info` del `spring-boot-maven-plugin`, no configurado hoy) o inyectar el SHA de git desde la
  plataforma de deploy. Mejora real, pero separada de "capturar errores".
- **`SentryHealthCheck`**: descartado, ver "Contexto y decisiones".
- Alertas/reglas de notificación dentro del dashboard de Sentry (Slack, email): configuración del lado
  de Sentry, no del código.
