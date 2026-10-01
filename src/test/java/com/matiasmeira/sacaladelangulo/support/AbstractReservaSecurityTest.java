package com.matiasmeira.sacaladelangulo.support;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base de los tests de autorización HTTP de las rutas de reservas (manual, por cancha, mis
 * reservas, agenda, mover de cancha y revertir ausencia). Es una capa sobre
 * {@link AbstractTurnoFijoSecurityTest} (que no se toca): reusa de ahí el contexto único, los
 * horarios de atención los 7 días, {@code canchaB}, {@code reservaRepository} y {@code maniana()}.
 * Suma una segunda cancha de A ({@link #canchaA2}), un helper para sembrar reservas por
 * repositorio y el POST de reserva manual con su Idempotency-Key.
 */
public abstract class AbstractReservaSecurityTest extends AbstractTurnoFijoSecurityTest {

    /** Mensaje del 403 de AutorizacionEmpleadoService.validarAccion (línea 59). */
    protected static final String MENSAJE_403_ACCION = "No autorizado para realizar esta acción en este establecimiento";

    /** Segunda cancha activa de A (PADEL): destino de los movimientos y "otra cancha" del aislamiento. */
    protected Cancha canchaA2;

    @BeforeEach
    void sembrarSegundaCanchaDeA() {
        canchaA2 = canchaRepository.save(Canchas.canchaActiva(establecimientoA));
    }

    /** Reserva de mañana 10:00-11:00 (PADEL) sembrada por repositorio. */
    protected Reserva reserva(Cancha cancha, Usuario jugadorONull, EstadoReserva estado) {
        return reserva(cancha, jugadorONull, estado, maniana().atTime(10, 0), 60);
    }

    /**
     * Reserva sembrada por repositorio (no por HTTP: los negativos no dependen de que el alta
     * funcione). Sin jugador queda como reserva manual a nombre de un cliente de mostrador.
     * precioTotal 1000, senaPagada 0; las PENDIENTE_SENA llevan expiraEn futuro.
     */
    protected Reserva reserva(Cancha cancha, Usuario jugadorONull, EstadoReserva estado,
                              LocalDateTime inicio, int minutos) {
        Reserva.ReservaBuilder builder = Reserva.builder()
                .cancha(cancha)
                .jugador(jugadorONull)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(minutos))
                .estado(estado)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO);
        if (jugadorONull == null) {
            builder.nombreClienteManual("Cliente Manual").telefonoClienteManual("1100000000");
        }
        if (estado == EstadoReserva.PENDIENTE_SENA) {
            builder.expiraEn(LocalDateTime.now().plusMinutes(10));
        }
        return reservaRepository.save(builder.build());
    }

    /** Body de POST /reservas/manual: mañana 10:00-11:00, PADEL, cliente de mostrador. */
    protected static String bodyManual(Cancha cancha) {
        return "{\"canchaId\":" + cancha.getId()
                + ",\"fechaHoraInicio\":\"" + maniana() + "T10:00:00\""
                + ",\"fechaHoraFin\":\"" + maniana() + "T11:00:00\""
                + ",\"deporteSeleccionado\":\"PADEL\""
                + ",\"nombreCliente\":\"Cliente Mostrador\",\"telefonoCliente\":\"1122334455\"}";
    }

    /** POST /reservas/manual con un Idempotency-Key nuevo en cada llamada (clave obligatoria en esa ruta). */
    protected ResultActions postManual(Cancha cancha, Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/reservas/manual")
                .header("Authorization", bearer(usuario))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyManual(cancha)));
    }
}
