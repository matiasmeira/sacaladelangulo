package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cálculo puro (sin acceso a base de datos) de disponibilidad de pool de canchas
 * físicas/lógicas: dada una cancha candidata y las reservas ya solapadas con el horario
 * que se quiere ocupar, determina si hay lugar para sumarla sin exceder la capacidad del
 * GRUPO de físicas que la involucra (ver Cancha.canchasFisicas/canchasNecesarias).
 * Extraído de ReservaService.validarPoolCanchas para que también lo use
 * DisponibilidadService al generar la grilla completa de turnos libres, donde consultar
 * la base de datos por cada uno de los cientos de slots candidatos sería inviable: ambos
 * servicios precargan una sola vez las canchas del establecimiento y llaman a este método
 * en memoria por cada candidato.
 *
 * Razona por GRUPO, no por pool individual: dos canchas lógicas que comparten físicas (sin
 * que una sea "pool de la otra", ej. Cancha9=[F1,F2,F3] y Cancha7=[F1,F2]) compiten por la
 * misma capacidad. El grupo es el cierre transitivo de todos los pools cuyos footprints de
 * físicas se intersectan entre sí, partiendo del footprint de la candidata. Dentro de un
 * grupo la asignación sigue siendo flexible (suma de demandas vs. capacidad del grupo, sin
 * importar qué físicas exactas ocupa cada reserva) — eso es válido porque CanchaService
 * exige que todo pool que comparta grupo sea idéntico a los demás (ver
 * CanchaService.validarConfiguracionDePool); bajo esa restricción la suma es exacta.
 */
@Slf4j
public final class PoolCanchaCalculator {

    private PoolCanchaCalculator() {
    }

    /**
     * Determina el conjunto de IDs de cancha que hay que bloquear (SELECT ... FOR UPDATE)
     * antes de validar y crear una reserva sobre {@code cancha}, para que la validación de
     * pool ({@link #hayDisponibilidad}) sea segura bajo concurrencia: incluye la cancha
     * solicitada, toda cancha física de su grupo, y toda cancha lógica cuyo pool intersecta
     * ese grupo (mismo criterio de grupo/cierre transitivo que usa hayDisponibilidad).
     */
    public static Set<Long> canchasRelacionadas(Cancha cancha, List<Cancha> todasLasCanchasDelEstablecimiento) {
        Set<Long> relacionadas = new HashSet<>();
        relacionadas.add(cancha.getId());

        Set<Long> grupo = calcularGrupo(cancha, todasLasCanchasDelEstablecimiento);
        if (grupo == null) {
            return relacionadas;
        }

        relacionadas.addAll(grupo);
        for (Cancha candidataDelEstablecimiento : todasLasCanchasDelEstablecimiento) {
            if (esPool(candidataDelEstablecimiento) && !Collections.disjoint(grupo, footprint(candidataDelEstablecimiento))) {
                relacionadas.add(candidataDelEstablecimiento.getId());
            }
        }
        return relacionadas;
    }

    public static boolean hayDisponibilidad(Cancha cancha, List<Reserva> solapadas, List<Cancha> todasLasCanchasDelEstablecimiento) {
        Set<Long> grupo = calcularGrupo(cancha, todasLasCanchasDelEstablecimiento);
        if (grupo == null) {
            // Ninguna cancha lógica del establecimiento referencia a "cancha" en su pool:
            // no hay ninguna restricción de pool que evaluar (la colisión exacta sobre la
            // misma cancha la resuelve validarCanchaExactaLibre, no esta clase).
            return true;
        }

        int usoActual = solapadas.stream()
                .filter(reservaSolapada -> grupo.containsAll(footprint(reservaSolapada.getCancha())))
                .mapToInt(reservaSolapada -> demanda(reservaSolapada.getCancha()))
                .sum();

        int usoNuevo = demanda(cancha);
        int capacidadGrupo = grupo.size();

        if (usoActual + usoNuevo > capacidadGrupo) {
            log.debug("Grupo de físicas sin disponibilidad. Cancha: {}, Grupo: {}, Uso actual: {}, Uso nuevo: {}, Capacidad: {}",
                    cancha.getId(), grupo, usoActual, usoNuevo, capacidadGrupo);
            return false;
        }
        return true;
    }

    private static boolean esPool(Cancha cancha) {
        return cancha.getCanchasFisicas() != null && !cancha.getCanchasFisicas().isEmpty();
    }

    /**
     * Footprint de físicas de una cancha: su propio pool si es lógica, o ella misma si no
     * tiene canchasFisicas (es una física "suelta", o cualquier otra cancha sin pool).
     * Cancha.canchasFisicas es un @ManyToMany que no filtra por isActive, así que una física
     * desactivada (fuera de servicio, ver CanchaService.desactivarCancha) se descarta acá:
     * sin este filtro, capacidadGrupo (grupo.size() en hayDisponibilidad) seguía contando una
     * física que ya no existe operativamente y el sistema vendía un cupo inexistente.
     */
    private static Set<Long> footprint(Cancha cancha) {
        if (esPool(cancha)) {
            Set<Long> ids = new HashSet<>();
            for (Cancha fisica : cancha.getCanchasFisicas()) {
                if (Boolean.TRUE.equals(fisica.getIsActive())) {
                    ids.add(fisica.getId());
                }
            }
            return ids;
        }
        return Set.of(cancha.getId());
    }

    /** Cantidad de físicas que consume una reserva/candidata sobre esta cancha. */
    private static int demanda(Cancha cancha) {
        if (esPool(cancha)) {
            Integer canchasNecesarias = cancha.getCanchasNecesarias();
            return (canchasNecesarias != null && canchasNecesarias > 0) ? canchasNecesarias : 1;
        }
        return 1;
    }

    /**
     * Cierre transitivo, como conjunto de IDs de físicas, de todos los pools del
     * establecimiento cuyo footprint se intersecta (directa o transitivamente) con el de
     * {@code cancha}. Devuelve {@code null} si "cancha" no participa de ningún pool (no es
     * lógica y ninguna lógica del establecimiento la referencia): en ese caso no hay grupo
     * que calcular, hayDisponibilidad no debe restringir nada.
     */
    private static Set<Long> calcularGrupo(Cancha cancha, List<Cancha> todasLasCanchasDelEstablecimiento) {
        List<Cancha> logicas = todasLasCanchasDelEstablecimiento.stream()
                .filter(PoolCanchaCalculator::esPool)
                .toList();

        Set<Long> grupo;
        if (esPool(cancha)) {
            grupo = new HashSet<>(footprint(cancha));
        } else if (logicas.stream().anyMatch(logica -> footprint(logica).contains(cancha.getId()))) {
            grupo = new HashSet<>();
            grupo.add(cancha.getId());
        } else {
            return null;
        }

        boolean cambio = true;
        while (cambio) {
            cambio = false;
            for (Cancha logica : logicas) {
                Set<Long> footprintLogica = footprint(logica);
                if (!Collections.disjoint(grupo, footprintLogica) && grupo.addAll(footprintLogica)) {
                    cambio = true;
                }
            }
        }
        return grupo;
    }
}
