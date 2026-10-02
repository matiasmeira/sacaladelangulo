package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.support.AbstractReservaSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/reservas (flujo del jugador, pendiente 112). Un OWNER no puede reservar por acá:
 * el @PreAuthorize lo deja pasar a propósito y ReservaService.crearReserva lo rechaza con un
 * AccessDeniedException de mensaje propio (el 403 genérico "Access Denied" no le dice nada al
 * front). PLAYER y ADMIN reservan; EMPLOYEE sigue con el 403 genérico de la anotación. La
 * reserva manual del dueño en su complejo (POST /reservas/manual) no cambia.
 */
@DisplayName("POST /api/v1/reservas - quién puede reservar como jugador")
class ReservaCrearAutorizacionTest extends AbstractReservaSecurityTest {

    private static final String MENSAJE_SOLO_JUGADORES = "Las reservas son para cuentas de jugador.";

    private ResultActions postReserva(Cancha cancha, Usuario usuario) throws Exception {
        String body = "{\"canchaId\":" + cancha.getId()
                + ",\"fechaHoraInicio\":\"" + maniana() + "T10:00:00\""
                + ",\"fechaHoraFin\":\"" + maniana() + "T11:00:00\""
                + ",\"deporteSeleccionado\":\"PADEL\"}";
        return mockMvc.perform(post("/api/v1/reservas")
                .header("Authorization", bearer(usuario))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("dueno_Devuelve403ConMensajeYNoCreaNada")
    void dueno_Devuelve403ConMensajeYNoCreaNada() throws Exception {
        postReserva(canchaA, duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_SOLO_JUGADORES));
        assertEquals(0, reservaRepository.count());
    }

    @Test
    @DisplayName("duenoReservandoEnOtroComplejo_Devuelve403ConMensajeYNoCreaNada")
    void duenoReservandoEnOtroComplejo_Devuelve403ConMensajeYNoCreaNada() throws Exception {
        postReserva(canchaA, duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_SOLO_JUGADORES));
        assertEquals(0, reservaRepository.count());
    }

    @Test
    @DisplayName("jugador_Devuelve201")
    void jugador_Devuelve201() throws Exception {
        postReserva(canchaA, jugador).andExpect(status().isCreated());
        assertEquals(1, reservaRepository.count());
    }

    @Test
    @DisplayName("admin_Devuelve201")
    void admin_Devuelve201() throws Exception {
        postReserva(canchaA, admin).andExpect(status().isCreated());
        assertEquals(1, reservaRepository.count());
    }

    @Test
    @DisplayName("empleado_Devuelve403PorAnotacion")
    void empleado_Devuelve403PorAnotacion() throws Exception {
        postReserva(canchaA, empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(0, reservaRepository.count());
    }

    @Test
    @DisplayName("duenoSigueReservandoManualEnSuComplejo_Devuelve201")
    void duenoSigueReservandoManualEnSuComplejo_Devuelve201() throws Exception {
        postManual(canchaA, duenoA).andExpect(status().isCreated());
        assertEquals(1, reservaRepository.count());
    }
}
