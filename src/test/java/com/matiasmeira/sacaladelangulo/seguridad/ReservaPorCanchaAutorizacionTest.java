package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
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

import java.util.EnumSet;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/reservas/cancha/{id}?fecha=. @PreAuthorize OWNER/ADMIN (ReservaController:172): un empleado
 * no entra ni con todos los permisos. El chequeo fino es validarPropietarioOAdmin sobre el establecimiento
 * de la cancha (ReservaService:948, mensaje en AutorizacionEmpleadoService:135). La respuesta expone el
 * nombre del jugador, que es el dato que protege la anotación.
 */
@DisplayName("GET /api/v1/reservas/cancha/{id}")
class ReservaPorCanchaAutorizacionTest extends AbstractReservaSecurityTest {

    private Reserva delJugadorEnA;
    private Reserva manualEnA;

    @BeforeEach
    void sembrarReservas() {
        delJugadorEnA = reserva(canchaA, jugador, EstadoReserva.CONFIRMADA);
        manualEnA = reserva(canchaA, null, EstadoReserva.CONFIRMADA, maniana().atTime(12, 0), 60);
        reserva(canchaA2, jugador, EstadoReserva.CONFIRMADA);
        reserva(canchaB, jugadorExtra(), EstadoReserva.CONFIRMADA);
    }

    private ResultActions porCancha(Long canchaId, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/reservas/cancha/" + canchaId)
                .param("fecha", maniana().toString())
                .header("Authorization", bearer(usuario)));
    }

    private void assertSoloLasDeCanchaA(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        delJugadorEnA.getId().intValue(), manualEnA.getId().intValue())))
                .andExpect(jsonPath("$.content[*].jugadorNombre", hasItem(jugador.getNombre())));
    }

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"jugador", "empleadoSinPermiso", "empleadoConOperarCaja", "empleadoConTodosLosPermisos"})
    @DisplayName("rolSinAcceso_Devuelve403PorAnotacion")
    void rolSinAcceso_Devuelve403PorAnotacion(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "jugador" -> jugador;
            case "empleadoSinPermiso" -> empleadoSinPermiso;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            default -> empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        };

        porCancha(canchaA.getId(), usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        porCancha(canchaA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200SoloConLasReservasDeEsaCancha")
    void duenoDelEstablecimiento_Devuelve200SoloConLasReservasDeEsaCancha() throws Exception {
        assertSoloLasDeCanchaA(porCancha(canchaA.getId(), duenoA));
    }

    @Test
    @DisplayName("admin_Devuelve200SoloConLasReservasDeEsaCancha")
    void admin_Devuelve200SoloConLasReservasDeEsaCancha() throws Exception {
        assertSoloLasDeCanchaA(porCancha(canchaA.getId(), admin));
    }

    @Test
    @DisplayName("canchaInexistente_Devuelve404")
    void canchaInexistente_Devuelve404() throws Exception {
        porCancha(999999L, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
    }
}
