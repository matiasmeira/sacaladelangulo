# Canche.ar — backend

API REST de [Canche.ar](https://www.canche.ar), una plataforma de reserva de canchas deportivas para Argentina.
Frontend: [github.com/matiasmeira/saque-front](https://github.com/matiasmeira/saque-front).

## El problema

Un complejo suele alquilar el mismo espacio físico para distintos deportes: una cancha de F7 que también se
divide en dos de F5. Si alguien reserva una de las F5, la F7 completa deja de estar disponible, pero la otra
F5 sigue libre y no debería bloquearse.

El modelo separa la **cancha física** de **lo que consume cada reserva**, y la disponibilidad se calcula sobre
esa relación (pool de canchas físicas por cancha lógica, `PoolCanchaCalculator` y `DisponibilidadService`), no
sobre un flag "ocupada" por cancha.

## Qué resuelve

- **Sin doble reserva, en dos capas.** Lock pesimista sobre las canchas relacionadas, tomado en orden de id
  para evitar deadlocks (`CanchaRepository.lockPorIds`, `ReservaService`), más un constraint
  `EXCLUDE USING gist` en la base como último respaldo (migración V10).
- **Seña y pre-reserva.** La reserva que exige seña nace `PENDIENTE_SENA` y un job (cada minuto) cancela las
  vencidas. Hoy el dueño o el admin confirma la seña a mano.
- **Regla de seña por plan.** TRIAL (seña opcional) y FREE (seña obligatoria, mínimo por cancha), siempre según
  el plan del dueño del complejo, aunque edite un admin. Hay jobs de fin de prueba y degradación de plan.
- **Turnos fijos** recurrentes, con renovación.
- **Modo Caja.** Un dispositivo del local se empareja con un código de un solo uso y recibe una cookie
  `HttpOnly`; los empleados entran con PIN y tienen permisos por acción (crear reserva manual, cobrar, cancelar,
  marcar ausente, vender en el buffet, operar caja, etc.).
- **Caja con turnos, cierre y arqueo** (saldo teórico contra saldo contado), buffet con stock y ventas, gastos
  y reportes.
- **Registro en dos pasos con verificación de mail**, para jugadores y para dueños.
- **Auditoría** de acciones de empleados y administrativas, y **feedback** de jugadores sobre los complejos.

## Decisiones técnicas

- **Monolito modular por feature.** Cada módulo repite `controller / dto / model / repository / service`;
  lo transversal vive en `core/`.
- **Autorización centralizada** en `AutorizacionEmpleadoService`, nunca inline en los services. La verifican
  `CoberturaPreAuthorizeTest` (recorre todos los endpoints registrados y exige `@PreAuthorize`), un barrido de
  401 sin token y tests HTTP con JWT real (MockMvc sobre la cadena de seguridad completa) por endpoint:
  dueño propio, dueño ajeno, admin, jugador y empleado con y sin permiso.
- **Idempotencia** en las mutaciones que mueven plata (`IdempotencyFilter`, header `Idempotency-Key`).
- **Rate limiting** por IP en autenticación y registro, y límites por identidad de negocio.
- **JWT con revocación:** el claim `tokenVersion` permite invalidar sesiones.
- **Flyway** con migraciones inmutables: las correcciones van en una migración nueva.
- **Soft-delete** en entidades con historial.
- **Tests de concurrencia y de queries contra Postgres real** (Testcontainers), en un job aparte del CI.
- **Mails** con plantillas Thymeleaf y Resend: reintentos persistidos y verificación de la firma Svix del
  webhook. En desarrollo, `LogEmailService` escribe los mails (y sus links) en el log.
- **Imágenes** en ImageKit. **Sentry** opcional (sin DSN queda deshabilitado).
- **Configuración de prod con fail-fast:** sin secretos requeridos la app no arranca; Swagger apagado fuera de
  desarrollo.

## Stack

Java 21, Spring Boot 3.5 (Web, Security, Data JPA, Validation, Actuator, Thymeleaf), PostgreSQL, Flyway, JWT
(jjwt), Caffeine, Resend, ImageKit, Sentry, springdoc-openapi, Lombok. Tests: JUnit 5, Mockito, Spring Security
Test, H2 y Testcontainers.

## Estructura

```
src/main/java/com/matiasmeira/sacaladelangulo/
  auth/ buffet/ caja/ cierrecaja/ cliente/ disponibilidad/ empleado/ establecimiento/
  feedback/ gastos/ mails/ publico/ reportes/ reserva/      # 14 módulos de negocio
  core/                                                      # seguridad, mails, idempotencia, rate limit, imágenes
src/main/resources/db/migration/                             # V1 a V28
```

## Correrlo en local

Requisitos: JDK 21 y PostgreSQL (para correr la app; los tests rápidos usan H2). Los tests de Testcontainers
necesitan Docker.

1. Copiá `.env.example` a `.env` y completalo. Spring Boot no lee `.env` por sí solo: se carga desde el
   `envFile` de la configuración de lanzamiento del IDE, o exportando las variables en la shell.
2. Perfil `dev` (`SPRING_PROFILES_ACTIVE=dev`): con `RESEND_ENABLED=false` los mails se simulan en el log.
   El perfil `prod` es el que trae el `Dockerfile`.
3. `./mvnw spring-boot:run`

Variables de entorno (detalle, defaults y cuáles son obligatorias en prod en `.env.example`):
`SPRING_PROFILES_ACTIVE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_DATASOURCE_URL`
(alternativa), `JWT_SECRET`, `FRONTEND_URL`, `CORS_ALLOWED_ORIGINS`, `RESEND_ENABLED`, `RESEND_API_KEY`,
`RESEND_WEBHOOK_SECRET`, `MAIL_FROM`, `IMAGEKIT_PRIVATE_KEY`, `SENTRY_DSN`; opcionales para ajustar vigencias
y pool (`JWT_EXPIRATION_MILLIS`, `JWT_EMPLEADO_EXPIRATION_MILLIS`, `CAJA_*`, `DB_POOL_*`, `DB_SSLMODE`).

Swagger UI: `/swagger-ui.html`, sólo con el perfil `dev`.

## Tests

```bash
./mvnw clean test                                                        # suite rápida: 1843 tests
./mvnw test -Dsurefire.excludedGroups= -Dgroups=testcontainers           # Postgres real (requiere Docker)
```

El CI (`.github/workflows/test.yml`, GitHub Actions) corre ambas suites en jobs separados.
