# Zona Pública — Follow-ups Design

**Status:** Approved by user, section by section, 2026-08-11.

## Context

The "zona pública del marketplace" feature (see `docs/superpowers/plans/2026-08-07-zona-publica-marketplace.md`) shipped on branch `test` after a full subagent-driven implementation + a final whole-branch review. That review flagged one Critical bug (already fixed) plus a handful of Important findings that were deliberately parked rather than rushed into the same fix wave. This spec covers three of those parked items, now approved for implementation:

1. `precioDesde`/`senaDesde` don't fall back to `Cancha.precioBase` when a cancha has no `Tarifa` rows.
2. The `fecha`/`hora` search filter in `ComplejoPublicoService.buscarComplejos` ignores opening hours, días no laborables, and cancha bloqueos — it only checks for overlapping reservas.
3. The no-location listing branch (`EstablecimientoRepository.findActivosPorDeporte`) has no upper bound on rows fetched.

## Goal 1: `precioDesde`/`senaDesde` fall back to `precioBase`

**Problem:** `precioDesde` is currently `min(Tarifa.precio)` across relevant canchas. A cancha with zero `Tarifa` rows contributes nothing, even though `PrecioReservaCalculator` already falls back to `Cancha.precioBase` (a required, never-null field) when no `Tarifa` matches a given booking. Result: a fully bookable complejo can show `precioDesde: null`, or a higher number than what a visitor would actually be charged.

`senaDesde` does NOT have this problem — `Cancha.montoSena` is itself a required, never-null field (not sourced from `Tarifa` at all), so it's already correct. No change needed there.

**Design:** for each relevant cancha, its price candidate becomes `min(precioBase, min(tarifas.precio))` instead of just `min(tarifas.precio)`. Concretely, extract a single private helper in `ComplejoPublicoService`:

```java
private BigDecimal precioMinimoDeCancha(Cancha cancha) {
    return Stream.concat(cancha.getTarifas().stream().map(Tarifa::getPrecio), Stream.of(cancha.getPrecioBase()))
            .min(Comparator.naturalOrder())
            .orElseThrow(); // precioBase es NOT NULL: el Stream nunca puede quedar vacío
}
```

and use it at all three current call sites that compute a "starting price":
- `construirCard` (listing): `precioDesde = relevantes.stream().map(this::precioMinimoDeCancha).min(Comparator.naturalOrder()).orElse(null)`
- `obtenerDetalle` (complejo-level): same shape, over `canchas` (unrestricted, no deporte filter at this endpoint).
- `obtenerDetalle` (per-cancha, `CanchaPublicaDto.precioDesde`): `precioMinimoDeCancha(c)` directly (never null now, since every cancha has a `precioBase`).

This also resolves the "derivation logic duplicated 3-4×" Minor finding from the final review — one helper instead of three inline `.flatMap(...).map(Tarifa::getPrecio).min(...)` chains (the `.flatMap` over tarifas alone is replaced by the helper, which additionally folds in `precioBase`).

**Explicitly out of scope (per your choice):** `Cancha.preciosPorDuracion` and `Tarifa.preciosPorDuracion` (per-duration price overrides) are NOT considered as candidates. They're a rarely-configured migration path today; including them would add real complexity for a marginal accuracy gain and doesn't change whether the result is `null`.

## Goal 2: fecha/hora search respects horarios, días no laborables, and bloqueos

**Problem:** `ComplejoPublicoService.filtrarPorDisponibilidad` currently only excludes a candidate complejo if ALL its active (deporte-matching) canchas have an overlapping `Reserva`. It never checks: is this a día no laborable? Is the requested hour within the complejo's `HorarioAtencion` for that weekday? Is the specific cancha blocked (`BloqueoCancha`) for maintenance during that window?

**Design:**

**2a. Extract the one genuinely tricky piece of logic — computing an opening window that may cross midnight — into a small, pure, reusable utility**, following this codebase's existing pattern (`GeoUtils`, `PoolCanchaCalculator`, both static-method-only classes in `establecimiento.service`). New class `HorarioAtencionCalculator`:

```java
package com.matiasmeira.sacaladelangulo.establecimiento.service;

public final class HorarioAtencionCalculator {
    private HorarioAtencionCalculator() {}

    public record VentanaHoraria(LocalDateTime inicio, LocalDateTime fin) {}

    /** Resuelve el horario de un HorarioAtencion para una fecha puntual, en LocalDateTime,
     * manejando el caso de cierre después de medianoche (cierre < apertura -> el cierre cae
     * al día siguiente). */
    public static VentanaHoraria calcularVentana(HorarioAtencion horario, LocalDate fecha) {
        LocalDateTime inicio = fecha.atTime(horario.getHoraApertura());
        LocalDateTime fin = horario.getHoraCierre().isBefore(horario.getHoraApertura())
                ? fecha.plusDays(1).atTime(horario.getHoraCierre())
                : fecha.atTime(horario.getHoraCierre());
        return new VentanaHoraria(inicio, fin);
    }
}
```

**This is a pure refactor of `DisponibilidadService`, not a behavior change there.** `DisponibilidadService.calcularDisponibilidadDelDia` currently computes `ventanaInicio`/`ventanaFin` inline with this exact logic — replace those two lines with a call to `HorarioAtencionCalculator.calcularVentana(horario, fecha)` and use `.inicio()`/`.fin()`. `DisponibilidadService`'s día-no-laborable/horario-existence checks (and their `motivoCierre` messaging) are untouched — only the window-math is extracted, so `DisponibilidadServiceTest` should pass unmodified.

**2b. Extend `ComplejoPublicoService.filtrarPorDisponibilidad`** to additionally exclude a candidate if:
- its `establecimientoId` has a matching `DiaNoLaborable` for the requested `fecha`, OR
- it has no `HorarioAtencion` for `fecha.getDayOfWeek()`, OR
- the requested `[inicioReserva, finReserva)` window doesn't fit entirely inside that `HorarioAtencion`'s `calcularVentana(...)` result (mirrors `DisponibilidadService.generarSlotsLibres`'s own "does the full slot fit" check — not just the start time), OR
- (per-cancha) the cancha has an overlapping `BloqueoCancha` for that window — folded into the existing "cancha no disponible" id-set alongside overlapping reservas.

**2c. New batch queries, matching the existing batched pattern in this same method** (canchas and reservas are already fetched in one query each for the whole candidate set, never per-establishment):

- `EstablecimientoRepository.precargarHorarios(List<Long> ids)`: same "session priming" trick as the existing `precargarFotos` — `@EntityGraph(attributePaths = {"horariosAtencion"})` on a `SELECT e FROM Establecimiento e WHERE e.id IN :ids` query, called for its side effect so `establecimiento.getHorariosAtencion()` doesn't lazy-load once per candidate (which would otherwise be a perf N+1 — not a crash, since we're inside the transaction, but still one query per candidate). `horariosAtencion` is a bag (`List`) and this is the *only* collection this particular priming query touches, so no `MultipleBagFetchException` risk.
- `DiaNoLaborableRepository.findByEstablecimientoIdInAndFecha(List<Long> establecimientoIds, LocalDate fecha)`: batch variant of the existing single-id `findByEstablecimientoIdAndFecha`.
- `BloqueoCanchaRepository.findByEstablecimientoIdInAndRango(List<Long> establecimientoIds, LocalDateTime inicio, LocalDateTime fin)`: batch variant of the existing single-id `findByEstablecimientoAndRango`.

## Goal 3: cap the no-location listing query

**Problem:** `EstablecimientoRepository.findActivosPorDeporte` (used by the home/no-location branch of `buscarComplejos`) has no `LIMIT` — it loads every active establishment (matching the deporte filter, if any) into memory before `ComplejoPublicoService` sorts and paginates. The location-based branch already has a real bound (bounding box + Haversine radius, now also clamped to a 100km max); this branch has none.

**Design:** add a `Pageable` parameter to `findActivosPorDeporte` and call it with a fixed cap (`PageRequest.of(0, 500)`) from `ComplejoPublicoService.buscarComplejos`. Spring Data applies the resulting `LIMIT`/`OFFSET` to the JPQL query as-is — no query rewrite needed, no aggregate/GROUP BY, no `Page`/count-query complexity (we return `List<Establecimiento>`, not `Page<Establecimiento>`, so there's no separate count query to keep in sync).

```java
List<Establecimiento> findActivosPorDeporte(@Param("deporte") Deporte deporte, Pageable pageable);
```

Trade-off, explicit and accepted: beyond the cap (500 active complejos matching the current filter), pagination on the no-location branch stops being exhaustive — a request for a page far beyond the cap returns nothing rather than digging further into the catalog. This bounds the worst-case query/memory cost permanently, which is the actual risk the final review flagged; true unbounded-catalog pagination (a DB-side rating aggregate query) was explicitly rejected as unnecessary complexity for a marketplace at this stage, consistent with why the original plan avoided that shape of query in the first place.

## Testing

- `precioMinimoDeCancha`: unit tests for (a) a cancha with tarifas cheaper than precioBase, (b) a cancha with tarifas more expensive than precioBase (precioBase wins), (c) a cancha with zero tarifas (precioBase is the only candidate, never null).
- `HorarioAtencionCalculator.calcularVentana`: unit tests for a same-day window (e.g. 09:00–23:00) and an overnight-crossing window (e.g. 20:00–02:00), asserting the returned `fin` lands on the correct date in each case.
- `DisponibilidadServiceTest`: must pass unmodified (behavior-preserving refactor) — run it explicitly to confirm.
- `filtrarPorDisponibilidad` (via `ComplejoPublicoServiceTest.buscarComplejos` cases): a complejo with no `HorarioAtencion` for the requested weekday is excluded; a complejo with a matching `DiaNoLaborable` is excluded; a cancha with an overlapping `BloqueoCancha` (but no overlapping `Reserva`) is treated as unavailable; a complejo whose `HorarioAtencion` doesn't cover the full requested window (e.g. closes at 22:00, search is for 21:30 with the default 60-minute window) is excluded; the overnight-crossing case (horario 20:00–02:00, search at 23:00) is included.
- `findActivosPorDeporte` with a `Pageable`: repository test confirming a `PageRequest.of(0, N)` caps the returned list at N rows even when more than N active establishments exist.

## Non-Goals

- `Cancha.preciosPorDuracion`/`Tarifa.preciosPorDuracion` as additional price candidates (explicitly deferred, see Goal 1).
- True DB-side pagination with a rating aggregate for the no-location branch beyond the fixed cap (explicitly deferred, see Goal 3).
- Any change to the authenticated app's own availability/pricing flows (`ReservaService`, `PrecioReservaCalculator`, the owner-facing `DisponibilidadController`) beyond the pure, behavior-preserving extraction in Goal 2a.
