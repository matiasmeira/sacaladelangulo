package com.matiasmeira.sacaladelangulo.reserva.repository;

import java.time.LocalDateTime;

/**
 * Proyección liviana (3 columnas, no la entidad completa) usada por los reportes de
 * ocupación: el prorrateo por franja horaria y el cruce con el horario de atención
 * configurable por día no es expresable en un GROUP BY JPQL portable, así que se trae
 * lo mínimo indispensable y se agrega en memoria (ver OcupacionCalculator).
 */
public interface ReservaOcupacionProjection {
    Long getCanchaId();
    LocalDateTime getFechaHoraInicio();
    LocalDateTime getFechaHoraFin();
}
