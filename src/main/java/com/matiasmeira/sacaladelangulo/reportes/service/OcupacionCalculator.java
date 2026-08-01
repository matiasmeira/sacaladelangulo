package com.matiasmeira.sacaladelangulo.reportes.service;

import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.reportes.dto.FranjaHoraria;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cálculo puro (sin acceso a base de datos) de horas disponibles/reservadas para el reporte
 * de ocupación. No es expresable como un único GROUP BY JPQL portable porque necesita
 * prorratear reservas y horario de atención entre franjas horarias (mañana/tarde/noche) día
 * por día, así que recibe datos ya traídos en proyecciones livianas (ver
 * ReservaOcupacionProjection) y hace el prorrateo acá, en memoria, sobre un dataset acotado
 * al establecimiento y rango de fechas pedidos.
 *
 * Horas disponibles = para cada día del rango con horario de atención (y que no sea un
 * DiaNoLaborable), horas de atención × canchas activas, menos las horas de BloqueoCancha que
 * se solapen con esa franja de atención. Asume que los bloqueos de una misma cancha no se
 * solapan entre sí (si lo hicieran, se restarían dos veces).
 */
public final class OcupacionCalculator {

    private OcupacionCalculator() {
    }

    public record CanchaInfo(Long id, String nombre) {
    }

    public record ReservaProyeccion(Long canchaId, LocalDateTime inicio, LocalDateTime fin) {
    }

    public record BloqueoProyeccion(Long canchaId, LocalDateTime inicio, LocalDateTime fin) {
    }

    public record HorasDisponiblesReservadas(BigDecimal disponibles, BigDecimal reservadas) {
    }

    public record Resultado(
            BigDecimal horasDisponibles,
            BigDecimal horasReservadas,
            Map<FranjaHoraria, HorasDisponiblesReservadas> porFranja,
            Map<Long, HorasDisponiblesReservadas> porCancha
    ) {
    }

    public static Resultado calcular(
            LocalDate desde,
            LocalDate hasta,
            List<HorarioAtencion> horariosAtencion,
            Set<LocalDate> diasNoLaborables,
            List<CanchaInfo> canchasActivas,
            List<BloqueoProyeccion> bloqueos,
            List<ReservaProyeccion> reservas
    ) {
        Map<DayOfWeek, HorarioAtencion> horarioPorDia = new EnumMap<>(DayOfWeek.class);
        for (HorarioAtencion h : horariosAtencion) {
            horarioPorDia.putIfAbsent(h.getDiaSemana(), h);
        }

        long minutosDisponiblesTotal = 0;
        Map<FranjaHoraria, Long> minutosDisponiblesPorFranja = inicializarFranjas();
        Map<Long, Long> minutosDisponiblesPorCancha = new HashMap<>();
        for (CanchaInfo cancha : canchasActivas) {
            minutosDisponiblesPorCancha.put(cancha.id(), 0L);
        }

        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            if (diasNoLaborables.contains(dia)) {
                continue;
            }
            HorarioAtencion horario = horarioPorDia.get(dia.getDayOfWeek());
            if (horario == null) {
                continue;
            }

            LocalDateTime aperturaDt = dia.atTime(horario.getHoraApertura());
            LocalDateTime cierreDt = horario.getHoraCierre().isAfter(horario.getHoraApertura())
                    ? dia.atTime(horario.getHoraCierre())
                    : dia.plusDays(1).atTime(horario.getHoraCierre());

            for (CanchaInfo cancha : canchasActivas) {
                long minutosBloqueados = minutosSolapadosConBloqueos(bloqueos, cancha.id(), aperturaDt, cierreDt);
                long minutosNetos = Math.max(Duration.between(aperturaDt, cierreDt).toMinutes() - minutosBloqueados, 0);

                minutosDisponiblesTotal += minutosNetos;
                minutosDisponiblesPorCancha.merge(cancha.id(), minutosNetos, Long::sum);

                for (FranjaHoraria franja : FranjaHoraria.values()) {
                    LocalDateTime[] ventana = franjaInterval(dia, franja);
                    long minutosAtencionEnFranja = minutosSolapados(aperturaDt, cierreDt, ventana[0], ventana[1]);
                    long minutosBloqueadosEnFranja = minutosSolapadosConBloqueos(bloqueos, cancha.id(), ventana[0], ventana[1]);
                    long minutosNetosFranja = Math.max(minutosAtencionEnFranja - minutosBloqueadosEnFranja, 0);
                    minutosDisponiblesPorFranja.merge(franja, minutosNetosFranja, Long::sum);
                }
            }
        }

        long minutosReservadosTotal = 0;
        Map<FranjaHoraria, Long> minutosReservadosPorFranja = inicializarFranjas();
        Map<Long, Long> minutosReservadosPorCancha = new HashMap<>();
        for (CanchaInfo cancha : canchasActivas) {
            minutosReservadosPorCancha.put(cancha.id(), 0L);
        }

        for (ReservaProyeccion reserva : reservas) {
            long duracion = Duration.between(reserva.inicio(), reserva.fin()).toMinutes();
            minutosReservadosTotal += duracion;
            minutosReservadosPorCancha.merge(reserva.canchaId(), duracion, Long::sum);

            for (LocalDate dia = reserva.inicio().toLocalDate(); !dia.isAfter(reserva.fin().toLocalDate()); dia = dia.plusDays(1)) {
                for (FranjaHoraria franja : FranjaHoraria.values()) {
                    LocalDateTime[] ventana = franjaInterval(dia, franja);
                    long solapado = minutosSolapados(reserva.inicio(), reserva.fin(), ventana[0], ventana[1]);
                    minutosReservadosPorFranja.merge(franja, solapado, Long::sum);
                }
            }
        }

        Map<FranjaHoraria, HorasDisponiblesReservadas> porFranja = new EnumMap<>(FranjaHoraria.class);
        for (FranjaHoraria franja : FranjaHoraria.values()) {
            porFranja.put(franja, new HorasDisponiblesReservadas(
                    horas(minutosDisponiblesPorFranja.get(franja)),
                    horas(minutosReservadosPorFranja.get(franja))));
        }

        Map<Long, HorasDisponiblesReservadas> porCancha = new HashMap<>();
        for (CanchaInfo cancha : canchasActivas) {
            porCancha.put(cancha.id(), new HorasDisponiblesReservadas(
                    horas(minutosDisponiblesPorCancha.get(cancha.id())),
                    horas(minutosReservadosPorCancha.getOrDefault(cancha.id(), 0L))));
        }

        return new Resultado(horas(minutosDisponiblesTotal), horas(minutosReservadosTotal), porFranja, porCancha);
    }

    private static Map<FranjaHoraria, Long> inicializarFranjas() {
        Map<FranjaHoraria, Long> mapa = new EnumMap<>(FranjaHoraria.class);
        for (FranjaHoraria franja : FranjaHoraria.values()) {
            mapa.put(franja, 0L);
        }
        return mapa;
    }

    private static long minutosSolapadosConBloqueos(List<BloqueoProyeccion> bloqueos, Long canchaId, LocalDateTime ventanaInicio, LocalDateTime ventanaFin) {
        long total = 0;
        for (BloqueoProyeccion bloqueo : bloqueos) {
            if (bloqueo.canchaId().equals(canchaId)) {
                total += minutosSolapados(ventanaInicio, ventanaFin, bloqueo.inicio(), bloqueo.fin());
            }
        }
        return total;
    }

    private static long minutosSolapados(LocalDateTime aInicio, LocalDateTime aFin, LocalDateTime bInicio, LocalDateTime bFin) {
        LocalDateTime inicio = aInicio.isAfter(bInicio) ? aInicio : bInicio;
        LocalDateTime fin = aFin.isBefore(bFin) ? aFin : bFin;
        return fin.isAfter(inicio) ? Duration.between(inicio, fin).toMinutes() : 0;
    }

    private static LocalDateTime[] franjaInterval(LocalDate dia, FranjaHoraria franja) {
        LocalDateTime inicio = dia.atTime(inicioFranja(franja));
        LocalDateTime fin = franja == FranjaHoraria.NOCHE ? dia.plusDays(1).atStartOfDay() : dia.atTime(finFranja(franja));
        return new LocalDateTime[]{inicio, fin};
    }

    private static LocalTime inicioFranja(FranjaHoraria franja) {
        return switch (franja) {
            case MANANA -> LocalTime.of(6, 0);
            case TARDE -> LocalTime.of(13, 0);
            case NOCHE -> LocalTime.of(19, 0);
        };
    }

    private static LocalTime finFranja(FranjaHoraria franja) {
        return switch (franja) {
            case MANANA -> LocalTime.of(13, 0);
            case TARDE -> LocalTime.of(19, 0);
            case NOCHE -> LocalTime.MIDNIGHT;
        };
    }

    private static BigDecimal horas(long minutos) {
        return BigDecimal.valueOf(minutos).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }
}
