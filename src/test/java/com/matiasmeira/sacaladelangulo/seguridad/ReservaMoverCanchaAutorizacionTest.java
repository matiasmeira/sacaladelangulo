package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractReservaSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/reservas/{id}/mover-cancha. @PreAuthorize OWNER/ADMIN (ReservaController:162). El chequeo
 * fino es validarPropietarioOAdmin sobre el establecimiento de la RESERVA (ReservaService:842, mensaje en
 * AutorizacionEmpleadoService:135): no mira el destino. Las reglas del movimiento (estados, misma cancha,
 * solapamiento, deporte) tienen sus tests de service.
 */
@DisplayName("PUT /api/v1/reservas/{id}/mover-cancha")
class ReservaMoverCanchaAutorizacionTest extends AbstractReservaSecurityTest {

    private Reserva reservaDeA;

    @BeforeEach
    void sembrarReserva() {
        reservaDeA = reserva(canchaA, jugador, EstadoReserva.CONFIRMADA);
    }

    private ResultActions mover(Long reservaId, Cancha destino, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/reservas/" + reservaId + "/mover-cancha")
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nuevaCanchaId\":" + destino.getId() + "}"));
    }

    private void assertSigueEnCanchaA() {
        Reserva actual = reservaRepository.findById(reservaDeA.getId()).orElseThrow();
        assertEquals(canchaA.getId(), actual.getCancha().getId());
    }

    private void assertMovidaACanchaA2ConElRestoIntacto() {
        Reserva actual = reservaRepository.findById(reservaDeA.getId()).orElseThrow();
        assertEquals(canchaA2.getId(), actual.getCancha().getId());
        assertEquals(EstadoReserva.CONFIRMADA, actual.getEstado());
        assertEquals(reservaDeA.getFechaHoraInicio(), actual.getFechaHoraInicio());
        assertEquals(reservaDeA.getFechaHoraFin(), actual.getFechaHoraFin());
        assertNotNull(actual.getJugador());
        assertEquals(jugador.getId(), actual.getJugador().getId());
        assertEquals(0, new BigDecimal("1000").compareTo(actual.getPrecioTotal()));
    }

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"jugador", "empleadoConOperarCaja", "empleadoConTodosLosPermisos"})
    @DisplayName("rolSinAcceso_Devuelve403PorAnotacion")
    void rolSinAcceso_Devuelve403PorAnotacion(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "jugador" -> jugador;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            default -> empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        };

        mover(reservaDeA.getId(), canchaA2, usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSigueEnCanchaA();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403AunqueElDestinoSeaSuyo")
    void duenoDeOtroEstablecimiento_Devuelve403AunqueElDestinoSeaSuyo() throws Exception {
        // ReservaService:842: la autorización mira el establecimiento de la reserva, no el del destino
        mover(reservaDeA.getId(), canchaB, duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertSigueEnCanchaA();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YMueveLaReserva")
    void duenoDelEstablecimiento_Devuelve200YMueveLaReserva() throws Exception {
        mover(reservaDeA.getId(), canchaA2, duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.canchaId").value(canchaA2.getId()));
        assertMovidaACanchaA2ConElRestoIntacto();
    }

    @Test
    @DisplayName("admin_Devuelve200YMueveLaReserva")
    void admin_Devuelve200YMueveLaReserva() throws Exception {
        mover(reservaDeA.getId(), canchaA2, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.canchaId").value(canchaA2.getId()));
        assertMovidaACanchaA2ConElRestoIntacto();
    }

    @Test
    @DisplayName("reservaInexistente_Devuelve404")
    void reservaInexistente_Devuelve404() throws Exception {
        mover(999999L, canchaA2, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Reserva no encontrada"));
        assertSigueEnCanchaA();
    }

    @Test
    @DisplayName("destinoInexistente_Devuelve404")
    void destinoInexistente_Devuelve404() throws Exception {
        mover(reservaDeA.getId(), Cancha.builder().id(999999L).build(), duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        assertSigueEnCanchaA();
    }
}
