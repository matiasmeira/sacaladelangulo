package com.matiasmeira.sacaladelangulo.disponibilidad.service;

import com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadCanchaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDiaResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadDuracionResponse;
import com.matiasmeira.sacaladelangulo.disponibilidad.dto.DisponibilidadEstablecimientoResponse;
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
import java.util.List;
import java.util.Optional;

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

    public DisponibilidadEstablecimientoResponse obtenerDisponibilidad(Long establecimientoId, LocalDate fechaInicio, LocalDate fechaFin) {
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
                .map(fecha -> calcularDisponibilidadDelDia(fecha, establecimiento, canchas, diasNoLaborables, bloqueos, reservas, ahora))
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
            List<DiaNoLaborable> diasNoLaborables, List<BloqueoCancha> bloqueos, List<Reserva> reservas, LocalDateTime ahora) {

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
        LocalDateTime ventanaInicio = fecha.atTime(horario.getHoraApertura());
        LocalDateTime ventanaFin = horario.getHoraCierre().isBefore(horario.getHoraApertura())
                ? fecha.plusDays(1).atTime(horario.getHoraCierre())
                : fecha.atTime(horario.getHoraCierre());

        List<DisponibilidadCanchaResponse> canchasResponse = canchas.stream()
                .map(cancha -> calcularDisponibilidadDeCancha(cancha, ventanaInicio, ventanaFin, canchas, bloqueos, reservas, ahora))
                .toList();

        return new DisponibilidadDiaResponse(fecha, true, null, canchasResponse);
    }

    private DisponibilidadCanchaResponse calcularDisponibilidadDeCancha(Cancha cancha, LocalDateTime ventanaInicio, LocalDateTime ventanaFin,
            List<Cancha> todasLasCanchas, List<BloqueoCancha> bloqueos, List<Reserva> reservas, LocalDateTime ahora) {

        List<DisponibilidadDuracionResponse> opciones = cancha.getDuracionesPermitidas().stream()
                .map(duracion -> new DisponibilidadDuracionResponse(duracion,
                        generarSlotsLibres(cancha, duracion, ventanaInicio, ventanaFin, todasLasCanchas, bloqueos, reservas, ahora)))
                .toList();

        return new DisponibilidadCanchaResponse(cancha.getId(), cancha.getNombre(), cancha.getDeportes(), opciones);
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

    private boolean seSuperponen(LocalDateTime inicioA, LocalDateTime finA, LocalDateTime inicioB, LocalDateTime finB) {
        return inicioA.isBefore(finB) && finA.isAfter(inicioB);
    }
}
