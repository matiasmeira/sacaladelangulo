package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cálculo puro (sin acceso a base de datos) de disponibilidad de pool de canchas
 * físicas/lógicas: dada una cancha candidata y las reservas ya solapadas con el horario
 * que se quiere ocupar, determina si hay lugar para sumarla sin exceder la capacidad de
 * ningún pool que la involucre (ver Cancha.canchasFisicas/canchasNecesarias).
 * Extraído de ReservaService.validarPoolCanchas para que también lo use
 * DisponibilidadService al generar la grilla completa de turnos libres, donde consultar
 * la base de datos por cada uno de los cientos de slots candidatos sería inviable: ambos
 * servicios precargan una sola vez las canchas del establecimiento y llaman a este método
 * en memoria por cada candidato.
 */
@Slf4j
public final class PoolCanchaCalculator {

    private PoolCanchaCalculator() {
    }

    /**
     * Determina el conjunto de IDs de cancha que hay que bloquear (SELECT ... FOR UPDATE)
     * antes de validar y crear una reserva sobre {@code cancha}, para que la validación de
     * pool ({@link #hayDisponibilidad}) sea segura bajo concurrencia: incluye la cancha
     * solicitada y toda cancha lógica/física que comparta un pool con ella, siguiendo el
     * mismo criterio de "afectaEstePool" que usa hayDisponibilidad.
     */
    public static Set<Long> canchasRelacionadas(Cancha cancha, List<Cancha> todasLasCanchasDelEstablecimiento) {
        Set<Long> relacionadas = new HashSet<>();
        relacionadas.add(cancha.getId());

        for (Cancha canchaDelPool : todasLasCanchasDelEstablecimiento) {
            if (canchaDelPool.getCanchasFisicas() == null || canchaDelPool.getCanchasFisicas().isEmpty()) {
                continue;
            }

            List<Long> poolIds = canchaDelPool.getCanchasFisicas().stream()
                    .map(Cancha::getId)
                    .toList();

            boolean afectaEstePool = canchaDelPool.getId().equals(cancha.getId()) || poolIds.contains(cancha.getId());
            if (afectaEstePool) {
                relacionadas.add(canchaDelPool.getId());
                relacionadas.addAll(poolIds);
            }
        }
        return relacionadas;
    }

    public static boolean hayDisponibilidad(Cancha cancha, List<Reserva> solapadas, List<Cancha> todasLasCanchasDelEstablecimiento) {
        for (Cancha canchaDelPool : todasLasCanchasDelEstablecimiento) {
            if (canchaDelPool.getCanchasFisicas() == null || canchaDelPool.getCanchasFisicas().isEmpty()) {
                continue;
            }

            List<Long> poolIds = canchaDelPool.getCanchasFisicas().stream()
                    .map(Cancha::getId)
                    .toList();

            boolean afectaEstePool = canchaDelPool.getId().equals(cancha.getId()) || poolIds.contains(cancha.getId());
            if (!afectaEstePool) {
                continue;
            }

            int usoActual = 0;
            for (Reserva reservaSolapada : solapadas) {
                Long canchaReservadaId = reservaSolapada.getCancha().getId();
                if (poolIds.contains(canchaReservadaId)) {
                    usoActual += 1;
                } else if (canchaReservadaId.equals(canchaDelPool.getId())) {
                    Integer canchasNecesarias = reservaSolapada.getCancha().getCanchasNecesarias();
                    if (canchasNecesarias != null && canchasNecesarias > 0) {
                        usoActual += canchasNecesarias;
                    }
                }
            }

            int usoNuevo = cancha.getId().equals(canchaDelPool.getId())
                    ? (cancha.getCanchasNecesarias() != null ? cancha.getCanchasNecesarias() : 1)
                    : 1;

            int capacidadPool = poolIds.size();
            if (usoActual + usoNuevo > capacidadPool) {
                log.debug("Pool sin disponibilidad. CanchaPool: {}, Uso actual: {}, Uso nuevo: {}, Capacidad: {}",
                        canchaDelPool.getId(), usoActual, usoNuevo, capacidadPool);
                return false;
            }
        }
        return true;
    }
}
