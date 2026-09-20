package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDiaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDuracionResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.RangoOcupadoResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.SlotDisponibleResponse;
import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.DiaNoLaborableRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoOperativoGuard;
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
import java.util.EnumSet;
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
    private final AutorizacionEmpleadoService autorizacionEmpleadoService;
    private final EstablecimientoOperativoGuard establecimientoOperativoGuard;

    /**
     * Hoy sólo la llama ComplejoPublicoService.obtenerDisponibilidad(slug,...), que ya resolvió
     * el establecimiento vía findBySlugOperativo antes de llegar acá -- por lo que este
     * chequeo es, para ese caller, siempre un no-op. Igual se revalida acá adentro (no sólo
     * confiar en el caller) para que cualquier otro punto de entrada que se agregue a futuro
     * sobre este método herede el mismo criterio sin tener que acordarse de repetirlo.
     *
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
        establecimientoOperativoGuard.validarEstablecimientoOperativoParaJugador(establecimiento);

        return calcularGrilla(establecimientoId, fechaInicio, fechaFinResuelta, establecimiento, incluirOcupacionPool);
    }

    /**
     * Único punto de entrada real de DisponibilidadController: el @PreAuthorize del endpoint
     * acepta PLAYER, OWNER, ADMIN y EMPLOYEE por igual, y este método es el que decide caso
     * por caso qué ve cada uno. Puebla ocupadaPorPool sólo cuando el usuario autenticado
     * tiene acceso de PANEL a ESE establecimiento (dueño, admin, o empleado con permiso
     * operativo de agenda) — estar autenticado no alcanza, porque registrarse es gratis.
     * Para quien no califica (otro jugador, o el dueño de OTRO establecimiento) el campo va
     * en null, igual que en la disponibilidad pública.
     *
     * <p>El gate de establecimiento activo NO se pregunta lo mismo que incluirOcupacionPool:
     * ese campo se puebla sólo con el subconjunto PERMISOS_OPERATIVOS_DE_RESERVA (más angosto,
     * pensado para quien opera la agenda), mientras que el control de acceso tiene que
     * preguntarse "¿esta persona pertenece a este establecimiento?" con CUALQUIER permiso --
     * un EMPLOYEE que sólo tiene, por ejemplo, OPERAR_CAJA es staff legítimo aunque no vea
     * ocupadaPorPool, y no debe recibir el 404 opaco reservado para quien no tiene ningún
     * vínculo con el establecimiento. Se calculan por separado a propósito: si el día de
     * mañana cambia qué permisos pueblan ocupadaPorPool, ese cambio no debe mover en
     * silencio el control de acceso de este gate.
     */
    public DisponibilidadEstablecimientoResponse obtenerDisponibilidadParaPanel(Long establecimientoId, LocalDate fechaInicio, LocalDate fechaFin,
            String email) {
        LocalDate fechaFinResuelta = fechaFin != null ? fechaFin : fechaInicio;
        validarRango(fechaInicio, fechaFinResuelta);

        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId)
                .orElseThrow(() -> new EntityNotFoundException("Establecimiento no encontrado"));

        boolean perteneceAEsteEstablecimiento = autorizacionEmpleadoService.tieneAccesoDePanel(
                establecimiento, email, EnumSet.allOf(PermisoEmpleado.class));

        if (!perteneceAEsteEstablecimiento) {
            establecimientoOperativoGuard.validarEstablecimientoOperativoParaJugador(establecimiento);
        }

        boolean incluirOcupacionPool = autorizacionEmpleadoService.tieneAccesoDePanel(
                establecimiento, email, AutorizacionEmpleadoService.PERMISOS_OPERATIVOS_DE_RESERVA);

        return calcularGrilla(establecimientoId, fechaInicio, fechaFinResuelta, establecimiento, incluirOcupacionPool);
    }

    private DisponibilidadEstablecimientoResponse calcularGrilla(Long establecimientoId, LocalDate fechaInicio, LocalDate fechaFinResuelta,
            Establecimiento establecimiento, boolean incluirOcupacionPool) {

        List<Cancha> canchas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimientoId);
        // Contexto aparte para PoolCanchaCalculator: incluye inactivas, porque una cancha
        // LÓGICA desactivada puede seguir con reservas futuras vigentes que hay que seguir
        // contando contra la capacidad del grupo (ver diagnóstico de sobreventa por lógica
        // desactivada). "canchas" (activas) sigue siendo la única fuente de qué filas se
        // dibujan en la grilla.
        List<Cancha> canchasParaPool = canchaRepository.findByEstablecimientoId(establecimientoId);
        List<DiaNoLaborable> diasNoLaborables = diaNoLaborableRepository
                .findByEstablecimientoIdAndFechaBetween(establecimientoId, fechaInicio, fechaFinResuelta);

        LocalDateTime rangoInicio = fechaInicio.atStartOfDay();
        LocalDateTime rangoFin = fechaFinResuelta.plusDays(1).atTime(LocalTime.MAX);
        List<BloqueoCancha> bloqueos = bloqueoCanchaRepository.findByEstablecimientoAndRango(establecimientoId, rangoInicio, rangoFin);

        LocalDateTime ahora = LocalDateTime.now();
        List<Reserva> reservas = reservaRepository.findSuperpuestas(establecimientoId, rangoInicio, rangoFin, ahora);

        List<DisponibilidadDiaResponse> dias = fechaInicio.datesUntil(fechaFinResuelta.plusDays(1))
                .map(fecha -> calcularDisponibilidadDelDia(fecha, establecimiento, canchas, canchasParaPool, diasNoLaborables, bloqueos, reservas,
                        ahora, incluirOcupacionPool))
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
            List<Cancha> canchasParaPool, List<DiaNoLaborable> diasNoLaborables, List<BloqueoCancha> bloqueos, List<Reserva> reservas,
            LocalDateTime ahora, boolean incluirOcupacionPool) {

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
                ? calcularOcupacionPorPool(canchas, canchasParaPool, reservas, ventanaInicio, ventanaFin)
                : null;

        List<DisponibilidadCanchaResponse> canchasResponse = canchas.stream()
                .map(cancha -> calcularDisponibilidadDeCancha(cancha, ventanaInicio, ventanaFin, canchasParaPool, bloqueos, reservas, ahora,
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
    private Map<Long, List<RangoOcupadoResponse>> calcularOcupacionPorPool(List<Cancha> canchas, List<Cancha> canchasParaPool,
            List<Reserva> reservas, LocalDateTime ventanaInicio, LocalDateTime ventanaFin) {

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
                if (!PoolCanchaCalculator.hayDisponibilidad(cancha, solapadasIntervalo, canchasParaPool)) {
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
