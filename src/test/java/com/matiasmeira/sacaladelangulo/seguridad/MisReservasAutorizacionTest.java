package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractReservaSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/reservas/mis-reservas. @PreAuthorize sólo PLAYER (ReservaController:209). No hay chequeo de
 * dueño: la identidad sale del token y el service filtra por jugador (ReservaService:988). Se prueba el
 * aislamiento: cada jugador ve sólo las suyas, no las de otro jugador ni las manuales.
 */
@DisplayName("GET /api/v1/reservas/mis-reservas")
class MisReservasAutorizacionTest extends AbstractReservaSecurityTest {

    private Usuario otroJugador;
    private Reserva delJugadorEnA;
    private Reserva delJugadorEnB;
    private Reserva delOtroJugador;

    @BeforeEach
    void sembrarReservas() {
        otroJugador = jugadorExtra();
        delJugadorEnA = reserva(canchaA, jugador, EstadoReserva.CONFIRMADA);
        delJugadorEnB = reserva(canchaB, jugador, EstadoReserva.CONFIRMADA, maniana().atTime(12, 0), 60);
        delOtroJugador = reserva(canchaA, otroJugador, EstadoReserva.CONFIRMADA, maniana().atTime(14, 0), 60);
        reserva(canchaA, null, EstadoReserva.CONFIRMADA, maniana().atTime(16, 0), 60);
    }

    private ResultActions misReservas(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/reservas/mis-reservas")
                .header("Authorization", bearer(usuario)));
    }

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"duenoA", "admin", "empleadoConOperarCaja", "empleadoSinPermiso"})
    @DisplayName("rolDistintoDeJugador_Devuelve403PorAnotacion")
    void rolDistintoDeJugador_Devuelve403PorAnotacion(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "duenoA" -> duenoA;
            case "admin" -> admin;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            default -> empleadoSinPermiso;
        };

        misReservas(usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("jugador_Devuelve200SoloConSusReservas")
    void jugador_Devuelve200SoloConSusReservas() throws Exception {
        misReservas(jugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        delJugadorEnA.getId().intValue(), delJugadorEnB.getId().intValue())));
    }

    @Test
    @DisplayName("otroJugador_Devuelve200SoloConLaSuya")
    void otroJugador_Devuelve200SoloConLaSuya() throws Exception {
        // la identidad sale del token: el otro jugador no ve las del primero
        misReservas(otroJugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(delOtroJugador.getId()));
    }

    @Test
    @DisplayName("sinFiltro_ExcluyePreReservasVencidas_YMuestraElRestoDeLosEstados")
    void sinFiltro_ExcluyePreReservasVencidas_YMuestraElRestoDeLosEstados() throws Exception {
        Reserva vencida = reserva(canchaA, jugador, EstadoReserva.CANCELADA_PRERESERVA, maniana().atTime(11, 0), 60);
        Reserva cancelada = reserva(canchaA, jugador, EstadoReserva.CANCELADA, maniana().atTime(12, 0), 60);
        Reserva pendiente = reserva(canchaA, jugador, EstadoReserva.PENDIENTE_SENA, maniana().atTime(13, 0), 60);
        Reserva finalizada = reserva(canchaA, jugador, EstadoReserva.FINALIZADA, maniana().atTime(14, 0), 60);
        Reserva ausente = reserva(canchaA, jugador, EstadoReserva.AUSENTE, maniana().atTime(15, 0), 60);

        misReservas(jugador).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        delJugadorEnA.getId().intValue(), delJugadorEnB.getId().intValue(),
                        cancelada.getId().intValue(), pendiente.getId().intValue(),
                        finalizada.getId().intValue(), ausente.getId().intValue())))
                .andExpect(jsonPath("$.content[?(@.id == " + vencida.getId() + ")]").isEmpty());
    }

    @Test
    @DisplayName("conFiltroExplicitoCanceladaPrereserva_RespetaLoPedido")
    void conFiltroExplicitoCanceladaPrereserva_RespetaLoPedido() throws Exception {
        Reserva vencida = reserva(canchaA, jugador, EstadoReserva.CANCELADA_PRERESERVA, maniana().atTime(11, 0), 60);

        mockMvc.perform(get("/api/v1/reservas/mis-reservas")
                        .param("estado", "CANCELADA_PRERESERVA")
                        .header("Authorization", bearer(jugador)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(vencida.getId()));
    }
}
