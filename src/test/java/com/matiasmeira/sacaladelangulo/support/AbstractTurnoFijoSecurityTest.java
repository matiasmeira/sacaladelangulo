package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.reserva.repository.TurnoFijoRepository;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base de los tests de autorización HTTP de turnos fijos. Hereda el escenario y el contexto único
 * de {@link AbstractSecurityWebTest} (que no se toca) y suma lo que sólo necesitan estos
 * endpoints:
 *
 * <ul>
 *   <li>Horario de atención los 7 días (06:00-23:00) en los dos establecimientos: sin horarios,
 *       el alta de un turno fijo da 400 "cerrado" (ReservaService:491-499) y ningún caso de éxito
 *       podría correr. Los 7 días evitan depender de qué día es "hoy".</li>
 *   <li>Una cancha propia de B ({@link #canchaB}), para sembrar series de B.</li>
 *   <li>Helpers para sembrar series por repositorio (no por HTTP: los negativos no dependen de
 *       que el POST de crear funcione) y para pegarle a POST /turnos-fijos con su
 *       Idempotency-Key (obligatorio en esa ruta: IdempotencyFilter:56-62).</li>
 * </ul>
 *
 * <p>Las series se siembran con una sola ocurrencia mañana 10:00-11:00 (PADEL). Un test que crea
 * un turno fijo por HTTP en la misma cancha y horario que una serie sembrada chocaría con ella:
 * por eso la siembra es explícita (helpers) y no está en el {@code @BeforeEach}.
 */
public abstract class AbstractTurnoFijoSecurityTest extends AbstractSecurityWebTest {

    protected static final LocalTime HORA_INICIO = LocalTime.of(10, 0);
    protected static final LocalTime HORA_FIN = LocalTime.of(11, 0);
    protected static final String NOMBRE_CLIENTE_SERIE = "Cliente Fijo";

    /** Mensaje del 403 de AutorizacionEmpleadoService.validarLectura (línea 78). */
    protected static final String MENSAJE_403_LECTURA = "No autorizado para ver esta información de este establecimiento";
    /** Mensaje del 403 de AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135). */
    protected static final String MENSAJE_403_PROPIETARIO = "No autorizado en este establecimiento";

    @Autowired
    protected TurnoFijoRepository turnoFijoRepository;

    @Autowired
    protected ReservaRepository reservaRepository;

    protected Cancha canchaB;

    @BeforeEach
    void sembrarHorariosYCanchaDeB() {
        establecimientoA = conHorarioLosSieteDias(establecimientoA);
        establecimientoB = conHorarioLosSieteDias(establecimientoB);
        canchaB = canchaRepository.save(Canchas.canchaActiva(establecimientoB));
    }

    private Establecimiento conHorarioLosSieteDias(Establecimiento establecimiento) {
        establecimiento.setHorariosAtencion(new ArrayList<>(Arrays.stream(DayOfWeek.values())
                .map(dia -> HorarioAtencion.builder()
                        .diaSemana(dia)
                        .horaApertura(LocalTime.of(6, 0))
                        .horaCierre(LocalTime.of(23, 0))
                        .establecimiento(establecimiento)
                        .build())
                .toList()));
        return establecimientoRepository.save(establecimiento);
    }

    protected static LocalDate maniana() {
        return LocalDate.now().plusDays(1);
    }

    /** Body de POST /turnos-fijos: una sola ocurrencia (mañana 10:00-11:00) a nombre de un cliente de mostrador. */
    protected static String bodyCrear(Cancha cancha) {
        LocalDate maniana = maniana();
        return "{\"canchaId\":" + cancha.getId()
                + ",\"fechaInicioPeriodo\":\"" + maniana + "\""
                + ",\"fechaFinPeriodo\":\"" + maniana + "\""
                + ",\"diaSemana\":\"" + maniana.getDayOfWeek() + "\""
                + ",\"horaInicio\":\"10:00\",\"horaFin\":\"11:00\""
                + ",\"deporteSeleccionado\":\"PADEL\""
                + ",\"nombreClienteManual\":\"Grupo Test\",\"telefonoClienteManual\":\"1122334455\"}";
    }

    /** POST /turnos-fijos con un Idempotency-Key nuevo (así ningún caso reusa la respuesta de otro). */
    protected ResultActions postCrear(Cancha cancha, Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/turnos-fijos")
                .header("Authorization", bearer(usuario))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyCrear(cancha)));
    }

    /**
     * Serie sembrada por repositorio sobre la cancha indicada. ACTIVO: con una Reserva CONFIRMADA
     * ligada (mañana 10:00-11:00), para poder verificar los efectos. CANCELADO: sólo la regla.
     */
    protected TurnoFijo serie(Cancha cancha, EstadoTurnoFijo estado) {
        LocalDate maniana = maniana();
        TurnoFijo serie = turnoFijoRepository.save(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(maniana.getDayOfWeek())
                .horaInicio(HORA_INICIO)
                .horaFin(HORA_FIN)
                .fechaInicioPeriodo(maniana)
                .fechaFinPeriodo(maniana)
                .nombreClienteManual(NOMBRE_CLIENTE_SERIE)
                .telefonoClienteManual("1100000000")
                .estado(estado)
                .canceladoDesde(estado == EstadoTurnoFijo.CANCELADO ? LocalDate.now() : null)
                .build());
        if (estado == EstadoTurnoFijo.ACTIVO) {
            LocalDateTime inicio = maniana.atTime(HORA_INICIO);
            reservaRepository.save(Reserva.builder()
                    .cancha(cancha)
                    .deporteSeleccionado(Deporte.PADEL)
                    .nombreClienteManual(NOMBRE_CLIENTE_SERIE)
                    .telefonoClienteManual("1100000000")
                    .fechaHoraInicio(inicio)
                    .fechaHoraFin(maniana.atTime(HORA_FIN))
                    .estado(EstadoReserva.CONFIRMADA)
                    .precioTotal(new BigDecimal("1000"))
                    .senaPagada(BigDecimal.ZERO)
                    .turnoFijo(serie)
                    .build());
        }
        return serie;
    }

    /** Serie ACTIVA de A con su reserva futura. */
    protected TurnoFijo serieDeA() {
        return serie(canchaA, EstadoTurnoFijo.ACTIVO);
    }
}
