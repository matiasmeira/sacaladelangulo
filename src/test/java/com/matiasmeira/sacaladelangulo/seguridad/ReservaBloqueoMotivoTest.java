package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.BloqueoCanchaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/reservas sobre un horario bloqueado (pendiente 59): el 400 no lleva el motivo del
 * bloqueo (notas internas del local) para nadie. El dueño lo sigue viendo en la agenda
 * (GET /bloqueos, ver BloqueoMotivoAutorizacionTest).
 */
@DisplayName("POST /api/v1/reservas - horario bloqueado sin motivo")
class ReservaBloqueoMotivoTest extends AbstractSecurityWebTest {

    private static final String MOTIVO = "Reclamo del proveedor de mantenimiento";
    private static final String MENSAJE = "La cancha se encuentra bloqueada en ese horario";

    @Autowired
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @BeforeEach
    void sembrarBloqueo() {
        LocalDate manana = LocalDate.now().plusDays(1);
        bloqueoCanchaRepository.save(BloqueoCancha.builder()
                .cancha(canchaA)
                .fechaInicio(manana.atTime(10, 0))
                .fechaFin(manana.atTime(12, 0))
                .motivo(MOTIVO)
                .build());
    }

    private void reservarBloqueado(Usuario usuario) throws Exception {
        LocalDate manana = LocalDate.now().plusDays(1);
        String body = "{\"canchaId\":" + canchaA.getId()
                + ",\"fechaHoraInicio\":\"" + manana.atTime(10, 0) + "\""
                + ",\"fechaHoraFin\":\"" + manana.atTime(11, 0) + "\""
                + ",\"deporteSeleccionado\":\"PADEL\"}";
        // Idempotency-Key es obligatorio en este POST (IdempotencyFilter.RUTAS_CLAVE_OBLIGATORIA)
        String respuesta = mockMvc.perform(post("/api/v1/reservas")
                        .header("Authorization", bearer(usuario))
                        .header("Idempotency-Key", "reserva-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(MENSAJE))
                .andReturn().getResponse().getContentAsString();
        assertFalse(respuesta.contains(MOTIVO));
    }

    @Test
    @DisplayName("jugador_ErrorSinMotivo")
    void jugador_ErrorSinMotivo() throws Exception {
        reservarBloqueado(jugador);
    }

    @Test
    @DisplayName("duenoDeOtroComplejoReservandoComoJugador_ErrorSinMotivo")
    void duenoDeOtroComplejoReservandoComoJugador_ErrorSinMotivo() throws Exception {
        reservarBloqueado(duenoB);
    }
}
