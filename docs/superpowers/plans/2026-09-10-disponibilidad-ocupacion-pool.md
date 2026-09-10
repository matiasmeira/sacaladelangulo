# Ocupación derivada del pool en disponibilidad — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Exponer en `DisponibilidadCanchaResponse` los rangos horarios en los que cada cancha queda ocupada por consumo de pool ajeno (no por bloqueo ni por su propia reserva), reutilizando `PoolCanchaCalculator.hayDisponibilidad`, y ocultar ese dato en el endpoint público.

**Architecture:** `DisponibilidadService.obtenerDisponibilidad` gana un parámetro `boolean incluirOcupacionPool`. Cuando es `true`, `calcularDisponibilidadDelDia` calcula, una vez por día y reutilizando las reservas/canchas ya precargadas, un mapa `canchaId -> List<RangoOcupadoResponse>` mediante puntos de corte (inicios/fines de reserva recortados a la ventana horaria) y una evaluación de `PoolCanchaCalculator.hayDisponibilidad` por intervalo y por cancha, fusionando intervalos contiguos al final. Cuando es `false`, el campo va en `null`. `DisponibilidadController` pasa `true`; `ComplejoPublicoService` pasa `false`.

**Tech Stack:** Java 17+, Spring Boot, JUnit 5, Mockito, AssertJ (según el archivo).

**Spec:** `docs/superpowers/specs/2026-09-10-disponibilidad-ocupacion-pool.md`

## Global Constraints

- No agregar ningún endpoint nuevo ni ninguna consulta nueva a la base de datos: todo se calcula en memoria con datos ya cargados por `obtenerDisponibilidad`.
- No tocar `generarSlotsLibres`, `estaLibre`, `alinearProximoInicio` ni el cálculo existente de `slotsLibres`.
- Reutilizar `PoolCanchaCalculator.hayDisponibilidad` tal cual existe hoy (`src/main/java/com/matiasmeira/sacaladelangulo/establecimiento/service/PoolCanchaCalculator.java`) — no reimplementar la regla de pool en `DisponibilidadService` ni en ningún otro lado.
- `ocupadaPorPool` es `null` cuando no se pidió (flag `false`) y una `List` (vacía o no) cuando sí se pidió (flag `true`). Nunca lista vacía como sustituto de "no se pidió".
- Excluir de `ocupadaPorPool` los rangos donde la propia cancha ya tiene una reserva (eso lo pinta el front vía `slotsLibres`/reserva directa, no este campo).

---

### Task 1: DTO nuevo + threading del flag `incluirOcupacionPool` + algoritmo de cálculo

**Files:**
- Create: `src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/dto/RangoOcupadoResponse.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/dto/DisponibilidadCanchaResponse.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadService.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/controller/DisponibilidadController.java`
- Modify: `src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceTest.java`
- Test: `src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java`

**Interfaces:**
- Produces: `RangoOcupadoResponse(LocalDateTime inicio, LocalDateTime fin)` — record usado por Task 2 y 3.
- Produces: `DisponibilidadCanchaResponse(Long canchaId, String canchaNombre, Set<Deporte> deportes, List<DisponibilidadDuracionResponse> opcionesDuracion, List<RangoOcupadoResponse> ocupadaPorPool)` — nueva forma del record, quinto campo `null`-able.
- Produces: `DisponibilidadService.obtenerDisponibilidad(Long establecimientoId, LocalDate fechaInicio, LocalDate fechaFin, boolean incluirOcupacionPool)` — nueva firma pública, usada por Task 2 y 3.

- [ ] **Step 1: Actualizar el test existente para la nueva firma (4 args) y verificar que sigue fallando solo por compilación**

En `DisponibilidadServiceTest.java`, todas las llamadas a `disponibilidadService.obtenerDisponibilidad(100L, fecha, X)` pasan a `disponibilidadService.obtenerDisponibilidad(100L, fecha, X, true)`. Son 8 call-sites (líneas 104, 111, 118, 131, 150, 164, 191, 214 del archivo actual). Reemplazá cada una, por ejemplo:

```java
    @Test
    @DisplayName("obtenerDisponibilidad lanza EntityNotFoundException si el establecimiento no existe")
    void lanzaExcepcionSiEstablecimientoNoExiste() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true));
    }
```

y así con el resto (`fecha.minusDays(1)`, `fecha.plusDays(40)`, `fecha, null` en los demás casos), agregando siempre `, true` como cuarto argumento.

Agregá además, al final de la clase, dos tests nuevos para el propio flag (usando el `establecimiento`/`cancha` simples ya definidos en `setUp()`, sin pool):

```java
    @Test
    @DisplayName("obtenerDisponibilidad trae ocupadaPorPool en null cuando el flag es false")
    void noIncluyeOcupacionPorPoolCuandoFlagEsFalse() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, false);

        DisponibilidadCanchaResponse canchaResponse = response.dias().get(0).canchas().get(0);
        assertEquals(null, canchaResponse.ocupadaPorPool());
    }

    @Test
    @DisplayName("obtenerDisponibilidad trae ocupadaPorPool como lista (no null) cuando el flag es true")
    void incluyeOcupacionPorPoolComoListaCuandoFlagEsTrue() {
        when(establecimientoRepository.findById(100L)).thenReturn(Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(cancha));
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(List.of());

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);

        DisponibilidadCanchaResponse canchaResponse = response.dias().get(0).canchas().get(0);
        assertEquals(List.of(), canchaResponse.ocupadaPorPool());
    }
```

En `ComplejoPublicoServiceTest.java`, el stub y la llamada de `obtenerDisponibilidad_ResuelveSlugYDelegaEnDisponibilidadService` (línea 742) pasan de:

```java
        when(disponibilidadService.obtenerDisponibilidad(1L, fecha, fecha)).thenReturn(respuestaEsperada);

        var resultado = complejoPublicoService.obtenerDisponibilidad("complejo-uno", fecha, fecha);

        assertEquals(respuestaEsperada, resultado);
    }
```

a:

```java
        when(disponibilidadService.obtenerDisponibilidad(1L, fecha, fecha, false)).thenReturn(respuestaEsperada);

        var resultado = complejoPublicoService.obtenerDisponibilidad("complejo-uno", fecha, fecha);

        assertEquals(respuestaEsperada, resultado);
        verify(disponibilidadService).obtenerDisponibilidad(1L, fecha, fecha, false);
    }
```

(agregá el import estático `import static org.mockito.Mockito.verify;` si el archivo no lo tiene ya).

- [ ] **Step 2: Correr los tests y verificar que fallan solo por compilación**

Run: `mvn -q -pl . test -Dtest=DisponibilidadServiceTest,ComplejoPublicoServiceTest` (desde `c:\Users\USER\Desktop\sacaladelangulo`)
Expected: FAIL — error de compilación, `obtenerDisponibilidad` no tiene una sobrecarga de 4 argumentos / `DisponibilidadCanchaResponse.ocupadaPorPool()` no existe.

- [ ] **Step 3: Crear el DTO `RangoOcupadoResponse`**

```java
package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import java.time.LocalDateTime;

/**
 * Rango en el que una cancha no es reservable por consumo de pool AJENO (no por
 * bloqueo, ni por una reserva propia de esa cancha — esas ya se reflejan en
 * slotsLibres).
 */
public record RangoOcupadoResponse(LocalDateTime inicio, LocalDateTime fin) {
}
```

- [ ] **Step 4: Agregar el campo `ocupadaPorPool` a `DisponibilidadCanchaResponse`**

Reemplazar el contenido completo de `DisponibilidadCanchaResponse.java` por:

```java
package com.matiasmeira.sacaladelangulo.disponibilidad.dto;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;

import java.util.List;
import java.util.Set;

/**
 * Disponibilidad de una cancha para un día puntual, desglosada por duración de turno.
 *
 * {@code ocupadaPorPool} lista los rangos en los que esta cancha no es reservable por
 * consumo de pool AJENO (no por bloqueo ni por su propia reserva, ya reflejados en
 * slotsLibres). Es {@code null} cuando no se pidió este dato (ver
 * DisponibilidadService#obtenerDisponibilidad) — la disponibilidad pública no lo pide,
 * para no exponer la ocupación interna del complejo — y una lista (vacía o no) cuando
 * sí se pidió.
 */
public record DisponibilidadCanchaResponse(
        Long canchaId,
        String canchaNombre,
        Set<Deporte> deportes,
        List<DisponibilidadDuracionResponse> opcionesDuracion,
        List<RangoOcupadoResponse> ocupadaPorPool
) {
}
```

- [ ] **Step 5: Threadear el flag y calcular `ocupadaPorPool` en `DisponibilidadService`**

Reemplazar el contenido completo de `DisponibilidadService.java` por:

```java
package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDiaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDuracionResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.RangoOcupadoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.SlotDisponibleResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.PoolCanchaCalculator;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Calcula la grilla consolidada de turnos disponibles de un establecimiento, cruzando en
 * el backend toda la información que hoy el frontend tenía que combinar por su cuenta:
 * horarios de atención, días no laborables, bloqueos de cancha, reservas existentes y el
 * pool de canchas físicas/lógicas (ver PoolCanchaCalculator).
 *
 * Toda la data (canchas, bloqueos y reservas del rango completo) se precarga en un puñado
 * de consultas antes de generar los slots candidatos, para poder evaluar cientos de
 * combinaciones día × cancha × duración × horario en memoria sin ejecutar una consulta
 * por cada una (evita N+1).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DisponibilidadService {

    private static final int RANGO_MAXIMO_DIAS = 31;

    private final EstablecimientoRepository establecimientoRepository;
    private final CanchaRepository canchaRepository;
    private final DiaNoLaborableRepository diaNoLaborableRepository;
    private final BloqueoCanchaRepository bloqueoCanchaRepository;
    private final ReservaRepository reservaRepository;

    /**
     * @param incluirOcupacionPool si es {@code true}, cada DisponibilidadCanchaResponse trae
     *                             en ocupadaPorPool los rangos donde esa cancha queda sin
     *                             cupo por consumo de pool ajeno; si es {@code false} (uso
     *                             público, ver ComplejoPublicoService) ese campo va en null.
     */
    public DisponibilidadEstablecimientoResponse obtenerDisponibilidad(Long establecimientoId, LocalDate fechaInicio, LocalDate fechaFin,
            boolean incluirOcupacionPool) {
        LocalDate fechaFinResuelta = fechaFin != null ? fechaFin : fechaInicio;
        validarRango(fechaInicio, fechaFinResuelta);

        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));

        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId);
        List<DiaNoLaborable> diasNoLaborables = diaNoLaborableRepository
                .findByEstablecimientoIdAndFechaBetween(establecimientoId, fechaInicio, fechaFinResuelta);

        LocalDateTime rangoInicio = fechaInicio.atStartOfDay();
        LocalDateTime rangoFin = fechaFinResuelta.plusDays(1).atTime(LocalTime.MAX);
        List<BloqueoCancha> bloqueos = bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, rangoInicio, rangoFin);

        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> reservas = reservaRepository.findSuperpuestas(establecimientoId, rangoInicio, rangoFin, ahora);

        List<DisponibilidadDiaResponse> dias = fechaInicio.datesUntil(fechaFinResuelta.plusDays(1))
                .map(fecha -> calcularDisponibilidadDelDia(fecha, establecimiento, canchas, diasNoLaborables, bloqueos, reservas, ahora,
                        incluirOcupacionPool))
                .toList();

        return new DisponibilidadEstablecimientoResponse(establecimientoId, fechaInicio, fechaFinResuelta, dias);
    }

    private void validarRango(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio == null) {
            throw new IllegalArgumentException("La fecha es obligatoria");
        }
        if (fechaFin.isBefore(fechaInicio)) {
            throw new IllegalArgumentException("La fecha de fin no puede ser anterior a la fecha de inicio");
        }
        if (ChronoUnit.DAYS.between(fechaInicio, fechaFin) >= RANGO_MAXIMO_DIAS) {
            throw new IllegalArgumentException("El rango de fechas no puede superar los " + RANGO_MAXIMO_DIAS + " días");
        }
    }

    private DisponibilidadDiaResponse calcularDisponibilidadDelDia(LocalDate fecha, Establecimiento establecimiento, List<Cancha> canchas,
            List<DiaNoLaborable> diasNoLaborables, List<BloqueoCancha> bloqueos, List<Reserva> reservas, LocalDateTime ahora,
            boolean incluirOcupacionPool) {

        Optional<DiaNoLaborable> diaNoLaborable = diasNoLaborables.stream()
                .filter(d -> d.getFecha().equals(fecha))
                .findFirst();
        if (diaNoLaborable.isPresent()) {
            String motivo = diaNoLaborable.get().getMotivo();
            String motivoCierre = (motivo == null || motivo.isBlank()) ? "Día no laborable" : motivo;
            return new DisponibilidadDiaResponse(fecha, false, motivoCierre, List.of());
        }

        Optional<HorarioAtencion> horarioOpt = establecimiento.getHorariosAtencion() == null ? Optional.empty()
                : establecimiento.getHorariosAtencion().stream()
                        .filter(h -> h.getDiaSemana() == fecha.getDayOfWeek())
                        .findFirst();
        if (horarioOpt.isEmpty()) {
            return new DisponibilidadDiaResponse(fecha, false, "El establecimiento está cerrado los " + fecha.getDayOfWeek(), List.of());
        }

        HorarioAtencion horario = horarioOpt.get();
        com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator.VentanaHoraria ventana =
                com.matiasmeira.sacaladelangulo.establecimiento.service.HorarioAtencionCalculator.calcularVentana(horario, fecha);
        LocalDateTime ventanaInicio = ventana.inicio();
        LocalDateTime ventanaFin = ventana.fin();

        Map<Long, List<RangoOcupadoResponse>> ocupacionPorPool = incluirOcupacionPool
                ? calcularOcupacionPorPool(canchas, reservas, ventanaInicio, ventanaFin)
                : null;

        List<DisponibilidadCanchaResponse> canchasResponse = canchas.stream()
                .map(cancha -> calcularDisponibilidadDeCancha(cancha, ventanaInicio, ventanaFin, canchas, bloqueos, reservas, ahora,
                        ocupacionPorPool == null ? null : ocupacionPorPool.getOrDefault(cancha.getId(), List.of())))
                .toList();

        return new DisponibilidadDiaResponse(fecha, true, null, canchasResponse);
    }

    private DisponibilidadCanchaResponse calcularDisponibilidadDeCancha(Cancha cancha, LocalDateTime ventanaInicio, LocalDateTime ventanaFin,
            List<Cancha> todasLasCanchas, List<BloqueoCancha> bloqueos, List<Reserva> reservas, LocalDateTime ahora,
            List<RangoOcupadoResponse> ocupadaPorPool) {

        List<DisponibilidadDuracionResponse> opciones = cancha.getDuracionesPermitidas().stream()
                .map(duracion -> new DisponibilidadDuracionResponse(duracion,
                        generarSlotsLibres(cancha, duracion, ventanaInicio, ventanaFin, todasLasCanchas, bloqueos, reservas, ahora)))
                .toList();

        return new DisponibilidadCanchaResponse(cancha.getId(), cancha.getNombre(), cancha.getDeportes(), opciones, ocupadaPorPool);
    }

    private List<SlotDisponibleResponse> generarSlotsLibres(Cancha cancha, int duracionMinutos, LocalDateTime ventanaInicio, LocalDateTime ventanaFin,
            List<Cancha> todasLasCanchas, List<BloqueoCancha> bloqueos, List<Reserva> reservas, LocalDateTime ahora) {

        boolean permiteMediaHora = Boolean.TRUE.equals(cancha.getPermiteInicioMediaHora());
        int paso = permiteMediaHora ? 30 : 60;

        List<SlotDisponibleResponse> slots = new ArrayList<>();
        LocalDateTime inicioSlot = alinearProximoInicio(ventanaInicio, permiteMediaHora);
        while (!inicioSlot.plusMinutes(duracionMinutos).isAfter(ventanaFin)) {
            LocalDateTime finSlot = inicioSlot.plusMinutes(duracionMinutos);
            if (!inicioSlot.isBefore(ahora) && estaLibre(cancha, inicioSlot, finSlot, todasLasCanchas, bloqueos, reservas)) {
                slots.add(new SlotDisponibleResponse(inicioSlot, finSlot));
            }
            inicioSlot = inicioSlot.plusMinutes(paso);
        }
        return slots;
    }

    /**
     * Redondea hacia adelante al próximo inicio válido según la granularidad de la
     * cancha: en punto y media (:00/:30), o solo en punto (:00) si no permite media hora.
     */
    private LocalDateTime alinearProximoInicio(LocalDateTime desde, boolean permiteMediaHora) {
        int paso = permiteMediaHora ? 30 : 60;
        int resto = desde.getMinute() % paso;
        return resto == 0 ? desde : desde.plusMinutes(paso - resto);
    }

    private boolean estaLibre(Cancha cancha, LocalDateTime inicio, LocalDateTime fin, List<Cancha> todasLasCanchas,
            List<BloqueoCancha> bloqueos, List<Reserva> reservas) {

        boolean bloqueada = bloqueos.stream()
                .filter(b -> b.getCancha().getId().equals(cancha.getId()))
                .anyMatch(b -> seSuperponen(b.getFechaInicio(), b.getFechaFin(), inicio, fin));
        if (bloqueada) {
            return false;
        }

        List<Reserva> solapadas = reservas.stream()
                .filter(r -> seSuperponen(r.getFechaHoraInicio(), r.getFechaHoraFin(), inicio, fin))
                .toList();

        boolean canchaExactaOcupada = solapadas.stream().anyMatch(r -> r.getCancha().getId().equals(cancha.getId()));
        if (canchaExactaOcupada) {
            return false;
        }

        return PoolCanchaCalculator.hayDisponibilidad(cancha, solapadas, todasLasCanchas);
    }

    /**
     * Para cada cancha, los rangos horarios del día en los que NO es reservable por
     * consumo de pool AJENO: una reserva nueva ahí sería rechazada por
     * PoolCanchaCalculator.hayDisponibilidad aunque esa cancha no tenga una reserva
     * propia en ese rango (eso ya lo refleja slotsLibres/la colisión exacta, y se excluye
     * acá para no pintarlo dos veces). Reutiliza las reservas y canchas ya precargadas
     * por obtenerDisponibilidad: no dispara consultas nuevas.
     *
     * Algoritmo: arma los puntos de corte con los inicios/fines de reserva del día
     * (recortados a la ventana horaria), evalúa hayDisponibilidad por cada intervalo
     * entre cortes consecutivos y por cada cancha, y fusiona al final los intervalos
     * contiguos de una misma cancha.
     */
    private Map<Long, List<RangoOcupadoResponse>> calcularOcupacionPorPool(List<Cancha> canchas, List<Reserva> reservas,
            LocalDateTime ventanaInicio, LocalDateTime ventanaFin) {

        List<Reserva> reservasDelDia = reservas.stream()
                .filter(r -> seSuperponen(r.getFechaHoraInicio(), r.getFechaHoraFin(), ventanaInicio, ventanaFin))
                .toList();

        TreeSet<LocalDateTime> puntosDeCorte = new TreeSet<>();
        for (Reserva reserva : reservasDelDia) {
            puntosDeCorte.add(clamp(reserva.getFechaHoraInicio(), ventanaInicio, ventanaFin));
            puntosDeCorte.add(clamp(reserva.getFechaHoraFin(), ventanaInicio, ventanaFin));
        }

        Map<Long, List<RangoOcupadoResponse>> ocupacionPorCancha = new LinkedHashMap<>();
        for (Cancha cancha : canchas) {
            ocupacionPorCancha.put(cancha.getId(), new ArrayList<>());
        }

        List<LocalDateTime> cortes = new ArrayList<>(puntosDeCorte);
        for (int i = 0; i < cortes.size() - 1; i++) {
            LocalDateTime inicioIntervalo = cortes.get(i);
            LocalDateTime finIntervalo = cortes.get(i + 1);

            List<Reserva> solapadasIntervalo = reservasDelDia.stream()
                    .filter(r -> seSuperponen(r.getFechaHoraInicio(), r.getFechaHoraFin(), inicioIntervalo, finIntervalo))
                    .toList();

            for (Cancha cancha : canchas) {
                boolean tieneReservaPropia = solapadasIntervalo.stream()
                        .anyMatch(r -> r.getCancha().getId().equals(cancha.getId()));
                if (tieneReservaPropia) {
                    continue;
                }
                if (!PoolCanchaCalculator.hayDisponibilidad(cancha, solapadasIntervalo, canchas)) {
                    ocupacionPorCancha.get(cancha.getId()).add(new RangoOcupadoResponse(inicioIntervalo, finIntervalo));
                }
            }
        }

        Map<Long, List<RangoOcupadoResponse>> fusionado = new LinkedHashMap<>();
        for (Map.Entry<Long, List<RangoOcupadoResponse>> entry : ocupacionPorCancha.entrySet()) {
            fusionado.put(entry.getKey(), fusionarRangosContiguos(entry.getValue()));
        }
        return fusionado;
    }

    private List<RangoOcupadoResponse> fusionarRangosContiguos(List<RangoOcupadoResponse> rangos) {
        if (rangos.isEmpty()) {
            return List.of();
        }
        List<RangoOcupadoResponse> fusionados = new ArrayList<>();
        RangoOcupadoResponse actual = rangos.get(0);
        for (int i = 1; i < rangos.size(); i++) {
            RangoOcupadoResponse siguiente = rangos.get(i);
            if (actual.fin().equals(siguiente.inicio())) {
                actual = new RangoOcupadoResponse(actual.inicio(), siguiente.fin());
            } else {
                fusionados.add(actual);
                actual = siguiente;
            }
        }
        fusionados.add(actual);
        return fusionados;
    }

    private LocalDateTime clamp(LocalDateTime valor, LocalDateTime minimo, LocalDateTime maximo) {
        if (valor.isBefore(minimo)) {
            return minimo;
        }
        if (valor.isAfter(maximo)) {
            return maximo;
        }
        return valor;
    }

    private boolean seSuperponen(LocalDateTime inicioA, LocalDateTime finA, LocalDateTime inicioB, LocalDateTime finB) {
        return inicioA.isBefore(finB) && finA.isAfter(inicioB);
    }
}
```

- [ ] **Step 6: Actualizar los dos callers**

En `DisponibilidadController.java`, cambiar la línea 39:
```java
        return ResponseEntity.ok(disponibilidadService.obtenerDisponibilidad(establecimientoId, fecha, fechaFin));
```
por:
```java
        return ResponseEntity.ok(disponibilidadService.obtenerDisponibilidad(establecimientoId, fecha, fechaFin, true));
```

En `ComplejoPublicoService.java`, cambiar la línea 410:
```java
        return disponibilidadService.obtenerDisponibilidad(establecimiento.getId(), fecha, fechaFin);
```
por:
```java
        return disponibilidadService.obtenerDisponibilidad(establecimiento.getId(), fecha, fechaFin, false);
```

Y actualizar el javadoc del método (líneas 399-406) para mencionar el flag, agregando esta línea al final del bloque existente:
```java
     * Pasa incluirOcupacionPool=false: la ocupación derivada de pool es información
     * interna del complejo (ver DisponibilidadCanchaResponse.ocupadaPorPool) que no debe
     * llegar al jugador anónimo.
```

- [ ] **Step 7: Correr los tests y verificar que pasan**

Run: `mvn -q -pl . test -Dtest=DisponibilidadServiceTest,ComplejoPublicoServiceTest` (desde `c:\Users\USER\Desktop\sacaladelangulo`)
Expected: PASS — todos los tests existentes más los dos nuevos de flag.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/dto/RangoOcupadoResponse.java \
        src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/dto/DisponibilidadCanchaResponse.java \
        src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadService.java \
        src/main/java/com/matiasmeira/sacaladelangulo/disponibilidad/controller/DisponibilidadController.java \
        src/main/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoService.java \
        src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceTest.java \
        src/test/java/com/matiasmeira/sacaladelangulo/publico/service/ComplejoPublicoServiceTest.java
git commit -m "feat(disponibilidad): agrega ocupadaPorPool a DisponibilidadCanchaResponse

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Tests del algoritmo con escenarios reales de pool (F1/F2/F3, Cancha7, Cancha9)

**Files:**
- Create: `src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceOcupacionPorPoolTest.java`

**Interfaces:**
- Consumes: `DisponibilidadService.obtenerDisponibilidad(Long, LocalDate, LocalDate, boolean)` (Task 1) y `Cancha.builder()`/`Reserva.builder()`/`Establecimiento.builder()`/`HorarioAtencion.builder()` (Lombok, ya existentes).
- Consumes: `DisponibilidadCanchaResponse.ocupadaPorPool()` (Task 1).

- [ ] **Step 1: Escribir la clase de test completa con los 6 escenarios de cálculo (fallando)**

```java
package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.RangoOcupadoResponse;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Ocupación derivada del pool (DisponibilidadCanchaResponse.ocupadaPorPool): una cancha
 * queda ahí en un rango si y solo si una reserva nueva sobre ella en ese rango sería
 * rechazada por PoolCanchaCalculator.hayDisponibilidad (ver
 * docs/superpowers/specs/2026-09-10-disponibilidad-ocupacion-pool.md).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisponibilidadService - ocupación derivada del pool")
class DisponibilidadServiceOcupacionPorPoolTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private CanchaRepository canchaRepository;

    @Mock
    private DiaNoLaborableRepository diaNoLaborableRepository;

    @Mock
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @InjectMocks
    private DisponibilidadService disponibilidadService;

    private Establecimiento establecimiento;
    private Cancha f1;
    private Cancha f2;
    private Cancha f3;
    private Cancha cancha7;
    private Cancha cancha9;
    private LocalDate fecha;

    @BeforeEach
    void setUp() {
        fecha = LocalDate.now().plusDays(30);

        establecimiento = Establecimiento.builder()
                .id(100L)
                .nombre("Complejo Test")
                .horariosAtencion(new ArrayList<>(List.of(
                        HorarioAtencion.builder()
                                .diaSemana(fecha.getDayOfWeek())
                                .horaApertura(LocalTime.of(14, 0))
                                .horaCierre(LocalTime.of(22, 0))
                                .build()
                )))
                .build();

        f1 = fisica(1L, "F1");
        f2 = fisica(2L, "F2");
        f3 = fisica(3L, "F3");
        cancha9 = logica(9L, "Cancha 9", Set.of(f1, f2, f3), 3);
        cancha7 = logica(7L, "Cancha 7", Set.of(f1, f2, f3), 2);

        when(establecimientoRepository.findById(100L)).thenReturn(java.util.Optional.of(establecimiento));
        when(diaNoLaborableRepository.findByEstablecimientoIdAndFechaBetween(100L, fecha, fecha)).thenReturn(List.of());
        when(bloqueoCanchaRepository.findByEstablecimientoAndRango(eq(100L), any(), any())).thenReturn(List.of());
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L))
                .thenReturn(List.of(f1, f2, f3, cancha7, cancha9));
    }

    private Cancha fisica(long id, String nombre) {
        return Cancha.builder()
                .id(id)
                .nombre(nombre)
                .establecimiento(establecimiento)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .isActive(true)
                .build();
    }

    private Cancha logica(long id, String nombre, Set<Cancha> pool, int canchasNecesarias) {
        return Cancha.builder()
                .id(id)
                .nombre(nombre)
                .establecimiento(establecimiento)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .duracionesPermitidas(List.of(60))
                .permiteInicioMediaHora(false)
                .isActive(true)
                .canchasFisicas(pool)
                .canchasNecesarias(canchasNecesarias)
                .build();
    }

    private Reserva reservaSobre(Cancha cancha, LocalTime horaInicio, LocalTime horaFin) {
        return Reserva.builder()
                .cancha(cancha)
                .estado(EstadoReserva.CONFIRMADA)
                .fechaHoraInicio(LocalDateTime.of(fecha, horaInicio))
                .fechaHoraFin(LocalDateTime.of(fecha, horaFin))
                .build();
    }

    private Map<Long, DisponibilidadCanchaResponse> obtenerCanchasPorId() {
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any())).thenReturn(reservasDelTest);
        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, true);
        return response.dias().get(0).canchas().stream()
                .collect(java.util.stream.Collectors.toMap(DisponibilidadCanchaResponse::canchaId, c -> c));
    }

    private List<Reserva> reservasDelTest;

    @Test
    @DisplayName("Cancha 9 (necesita 3) reservada 17-18 ocupa por pool a F1, F2, F3 y Cancha 7")
    void cancha9Reservada_ocupaTodoElGrupo() {
        reservasDelTest = List.of(reservaSobre(cancha9, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        List<RangoOcupadoResponse> rangoEsperado = List.of(
                new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(17, 0)), LocalDateTime.of(fecha, LocalTime.of(18, 0))));
        assertEquals(rangoEsperado, canchas.get(1L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(2L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(3L).ocupadaPorPool());
        assertEquals(rangoEsperado, canchas.get(7L).ocupadaPorPool());
        assertTrue(canchas.get(9L).ocupadaPorPool().isEmpty());
    }

    @Test
    @DisplayName("Cancha 7 (necesita 2) reservada 15-16 deja libres a F1, F2, F3 y ocupa a Cancha 9")
    void cancha7Reservada_dejaFisicasLibresYOcupaCancha9() {
        reservasDelTest = List.of(reservaSobre(cancha7, LocalTime.of(15, 0), LocalTime.of(16, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertEquals(
                List.of(new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(15, 0)), LocalDateTime.of(fecha, LocalTime.of(16, 0)))),
                canchas.get(9L).ocupadaPorPool());
    }

    @Test
    @DisplayName("Una física suelta reservada no ocupa a las otras físicas, sí a Cancha 9 sin cupo")
    void fisicaSueltaReservada_noOcupaOtrasFisicas_siOcupaCancha9() {
        reservasDelTest = List.of(reservaSobre(f1, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertEquals(
                List.of(new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(17, 0)), LocalDateTime.of(fecha, LocalTime.of(18, 0)))),
                canchas.get(9L).ocupadaPorPool());
    }

    @Test
    @DisplayName("Dos reservas contiguas del mismo grupo fusionan el rango derivado en uno solo")
    void reservasContiguas_fusionanElRangoDerivado() {
        reservasDelTest = List.of(
                reservaSobre(cancha9, LocalTime.of(15, 0), LocalTime.of(16, 0)),
                reservaSobre(cancha9, LocalTime.of(16, 0), LocalTime.of(17, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        List<RangoOcupadoResponse> rangoEsperado = List.of(
                new RangoOcupadoResponse(LocalDateTime.of(fecha, LocalTime.of(15, 0)), LocalDateTime.of(fecha, LocalTime.of(17, 0))));
        assertEquals(rangoEsperado, canchas.get(1L).ocupadaPorPool());
        assertEquals(1, canchas.get(1L).ocupadaPorPool().size());
    }

    @Test
    @DisplayName("Una prereserva PENDIENTE_SENA vencida no genera ocupación derivada")
    void prereservaVencida_noGeneraOcupacionDerivada() {
        // ReservaRepository.findSuperpuestas ya excluye en la propia query JPQL (estado ==
        // PENDIENTE_SENA && expiraEn <= ahora): una prereserva vencida nunca llega a
        // DisponibilidadService, así que acá se simula exactamente ese contrato devolviendo
        // en el mock la lista SIN la prereserva vencida (tal como haría la query real).
        // Si esa prereserva vencida existiera sobre Cancha 9 17-18 y por un bug se colara
        // igual, F1/F2/F3/Cancha7 aparecerían ocupados 17-18 (mismo escenario que
        // cancha9Reservada_ocupaTodoElGrupo) — por eso alcanza con probar que, en su
        // ausencia correcta, ninguna cancha del grupo queda ocupada por pool en ese horario.
        reservasDelTest = List.of();

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(7L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(9L).ocupadaPorPool().isEmpty());
    }

    @Test
    @DisplayName("Sin canchas lógicas, ocupadaPorPool es vacío en todas las canchas")
    void sinCanchasLogicas_ocupadaPorPoolVacioEnTodas() {
        when(canchaRepository.findByEstablecimientoIdAndIsActiveTrue(100L)).thenReturn(List.of(f1, f2, f3));
        reservasDelTest = List.of(reservaSobre(f1, LocalTime.of(17, 0), LocalTime.of(18, 0)));

        Map<Long, DisponibilidadCanchaResponse> canchas = obtenerCanchasPorId();

        assertTrue(canchas.get(1L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(2L).ocupadaPorPool().isEmpty());
        assertTrue(canchas.get(3L).ocupadaPorPool().isEmpty());
    }
}
```

- [ ] **Step 2: Correr los tests y verificar que todos pasan**

Run: `mvn -q -pl . test -Dtest=DisponibilidadServiceOcupacionPorPoolTest` (desde `c:\Users\USER\Desktop\sacaladelangulo`)
Expected: PASS. Si algo falla, el algoritmo de `calcularOcupacionPorPool` (Task 1, Step 5) tiene un bug — no reimplementar la regla en el test, corregir `DisponibilidadService`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceOcupacionPorPoolTest.java
git commit -m "test(disponibilidad): cubre ocupadaPorPool con escenarios reales de pool

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Test explícito de visibilidad pública (flag false -> null)

**Files:**
- Modify: `src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceOcupacionPorPoolTest.java`

**Interfaces:**
- Consumes: mismo helper `obtenerCanchasPorId()` y setup de Task 2, pero llamando a `obtenerDisponibilidad(..., false)` directamente en el test (sin pasar por el helper).

- [ ] **Step 1: Agregar el test de visibilidad pública (fallando si el flag no propagara null)**

Agregar al final de la clase `DisponibilidadServiceOcupacionPorPoolTest` (antes del cierre `}`):

```java
    @Test
    @DisplayName("Con incluirOcupacionPool=false (uso público) ocupadaPorPool viaja en null")
    void flagFalse_ocupadaPorPoolEsNullParaTodasLasCanchas() {
        when(reservaRepository.findSuperpuestas(eq(100L), any(), any(), any()))
                .thenReturn(List.of(reservaSobre(cancha9, LocalTime.of(17, 0), LocalTime.of(18, 0))));

        DisponibilidadEstablecimientoResponse response = disponibilidadService.obtenerDisponibilidad(100L, fecha, null, false);

        for (DisponibilidadCanchaResponse cancha : response.dias().get(0).canchas()) {
            assertEquals(null, cancha.ocupadaPorPool());
        }
    }
```

Esto ya cubre, a nivel del propio `DisponibilidadService`, exactamente el dato que recibe `ComplejoPublicoService` (que llama con `false`, ver Task 1 Step 6) — que es lo que finalmente expone el endpoint público `/api/v1/publico/complejos/{slug}/disponibilidad`.

- [ ] **Step 2: Correr el test y verificar que pasa**

Run: `mvn -q -pl . test -Dtest=DisponibilidadServiceOcupacionPorPoolTest#flagFalse_ocupadaPorPoolEsNullParaTodasLasCanchas` (desde `c:\Users\USER\Desktop\sacaladelangulo`)
Expected: PASS.

- [ ] **Step 3: Correr toda la suite del módulo de disponibilidad y público para verificar que no hay regresiones**

Run: `mvn -q -pl . test -Dtest=DisponibilidadServiceTest,DisponibilidadServiceOcupacionPorPoolTest,ComplejoPublicoServiceTest,ComplejoPublicoControllerIntegrationTest` (desde `c:\Users\USER\Desktop\sacaladelangulo`)
Expected: PASS — todo verde, sin tocar `slotsLibres` ni el cálculo existente.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/matiasmeira/sacaladelangulo/disponibilidad/service/DisponibilidadServiceOcupacionPorPoolTest.java
git commit -m "test(disponibilidad): verifica que ocupadaPorPool viaja en null cuando no se pide

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
