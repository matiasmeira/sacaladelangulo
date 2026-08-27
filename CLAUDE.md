# sacaladelangulo

Backend de una plataforma de reserva de canchas deportivas: marketplace de establecimientos y canchas, reservas, disponibilidad, buffet, caja/cierre de caja, gastos, empleados y reportes.

## Idioma

Responder siempre en español, sin importar el idioma en que esté escrito el prompt del usuario.

## Stack

- **Java 21** + **Spring Boot 3.5.14**, Maven (usar el wrapper: `./mvnw`)
- **Spring Security** con JWT propio (`io.jsonwebtoken:jjwt`), sin proveedor externo (OAuth/Keycloak)
- **Spring Data JPA** + **PostgreSQL** en runtime; **H2** en memoria para tests unitarios
- **Flyway** para migraciones (`src/main/resources/db/migration`, convención `V{n}__descripcion.sql`)
- **Testcontainers** (Postgres real) para tests de integración/concurrencia — tageados `@Tag("testcontainers")` y excluidos del `./mvnw test` normal porque necesitan Docker
- **Lombok**
- **springdoc-openapi** (swagger-ui)
- **Thymeleaf** + OGNL para plantillas de email, **Resend** para el envío real
- **JUnit 5** + Mockito + Spring Security Test

## Arquitectura

Organización **por feature**, no por capa técnica:

```
auth/ buffet/ caja/ cierrecaja/ cliente/ disponibilidad/ empleado/
establecimiento/ feedback/ gastos/ mails/ publico/ reportes/ reserva/
```

Cada módulo repite el mismo layout interno: `controller/ dto/ model/ repository/ service/`.

`core/` contiene lo transversal: `config/security`, `email`, `exception` (`GlobalExceptionHandler`), `idempotencia`, `pago`, `ratelimit`.

## Build y tests

- `./mvnw test` — suite normal, excluye los tests de Testcontainers.
- `./mvnw test -Dsurefire.excludedGroups= -Dgroups=testcontainers` — corre también los tests de concurrencia con Postgres real (necesita Docker corriendo).
- Perfiles `application-dev.properties` / `application-prod.properties` sobre el `application.properties` base.

## Buenas prácticas del proyecto

- **Soft-delete, no DELETE físico** en entidades con historial (`Usuario.deletedAt`, `Gasto.isActive`, etc.). Filtrar siempre por el discriminador explícito y correcto — por ejemplo `deletedAt`, no `isActive`, que está sobrecargado con otros significados (onboarding incompleto en `PLAYER`, desactivación de `EMPLOYEE`).
- **Auditoría**: acciones administrativas o sensibles se registran vía `RegistroAuditoriaService` + enum `AccionAuditoria`.
- **Idempotencia**: mutaciones sensibles a duplicados pasan por `IdempotencyFilter` (header `Idempotency-Key`).
- **Las migraciones de Flyway ya aplicadas son inmutables** — nunca editar una `V{n}__*.sql` existente; cualquier corrección va en una migración nueva.
- **Antes de una feature no trivial**, revisar `docs/superpowers/specs/` y `docs/superpowers/plans/`: este repo documenta diseño e implementación ahí antes de escribir código.
- Los tests de concurrencia y constraints de base van contra Postgres real vía Testcontainers, no mockeados — no bajar esa cobertura a mocks.

## Formato de commits

A partir de ahora, todos los commits de este repositorio se escriben en **español**, siguiendo **Conventional Commits**, en modo imperativo:

```
tipo(scope opcional): descripción corta en minúscula, sin punto final
```

Tipos permitidos: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`, `style`, `build`, `ci`.

Reglas:

- Modo imperativo: "agrega", "corrige", "elimina" — nunca "agregado", "agregando" ni "se agrega".
- Tono seco, técnico y directo. Describir QUÉ cambió (clase, endpoint, comportamiento, bug concreto), no adjetivos de calidad.
- **Prohibidas las palabras de relleno tipo IA**: robusto, eficiente, optimizado, dinámico, seamless, comprehensive, mejorado/mejora (como verbo vago), potente, flexible, escalable, sólido.
- Una sola línea si el commit es simple. Si toca varias cosas relevantes, agregar cuerpo: línea en blanco + viñetas simples (`- `), cada una corta y factual.
- No inventar intención que no esté en el diff: si no se puede confirmar el motivo, describir el cambio mecánico.
- Nada de mensajes en inglés ni genéricos tipo "fix stuff", "wip", "cambios varios".

Ejemplos:

```
fix(reserva): corrige condición de carrera que permitía doble booking

- agrega CanchaRepository.lockPorIds con SELECT ... FOR UPDATE
- aplica el lock antes de validar solapamientos en crearReserva
```

```
refactor(disponibilidad): extrae HorarioAtencionCalculator de DisponibilidadService (sin cambio de comportamiento)
```
