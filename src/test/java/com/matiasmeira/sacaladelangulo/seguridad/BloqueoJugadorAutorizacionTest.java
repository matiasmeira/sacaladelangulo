package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/jugadores-bloqueados. Sólo se bloquea a un jugador con al menos una
 * reserva en ese complejo (misma regla que la ficha de Clientes); cualquier otro id (inexistente, no
 * jugador o sin reservas ahí) responde el MISMO 404, para no filtrar nombre/email ni enumerar ids.
 */
@DisplayName("Bloqueo de jugadores /api/v1/establecimientos/{id}/jugadores-bloqueados")
class BloqueoJugadorAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_UNICO = "Jugador no encontrado en este establecimiento";

    @Autowired
    private ReservaRepository reservaRepository;

    private String ruta() {
        return "/api/v1/establecimientos/" + establecimientoA.getId() + "/jugadores-bloqueados";
    }

    private ResultActions bloquear(Long jugadorId, Usuario quien) throws Exception {
        return mockMvc.perform(post(ruta()).header("Authorization", bearer(quien))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jugadorId\":" + jugadorId + ",\"motivo\":\"No-show\"}"));
    }

    private Usuario jugadorConReservaEnA() {
        Usuario cliente = jugadorExtra();
        LocalDateTime inicio = LocalDateTime.now().minusDays(2).withHour(18).withMinute(0).withSecond(0).withNano(0);
        reservaRepository.save(Reserva.builder()
                .cancha(canchaA)
                .jugador(cliente)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(90))
                .estado(EstadoReserva.FINALIZADA)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO)
                .build());
        return cliente;
    }

    private void assertNoEncontrado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(MENSAJE_UNICO))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.nombre").doesNotExist());
    }

    @Test
    @DisplayName("duenoPropio_jugadorConReservaEnElComplejo_Devuelve201")
    void duenoPropio_jugadorConReserva_Devuelve201() throws Exception {
        Usuario cliente = jugadorConReservaEnA();
        bloquear(cliente.getId(), duenoA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jugadorId").value(cliente.getId()));
    }

    @Test
    @DisplayName("admin_jugadorConReservaEnElComplejo_Devuelve201")
    void admin_jugadorConReserva_Devuelve201() throws Exception {
        Usuario cliente = jugadorConReservaEnA();
        bloquear(cliente.getId(), admin).andExpect(status().isCreated())
                .andExpect(jsonPath("$.jugadorId").value(cliente.getId()));
    }

    @Test
    @DisplayName("duenoPropio_jugadorSinReservasEnElComplejo_Devuelve404Unico")
    void duenoPropio_jugadorSinReservas_Devuelve404() throws Exception {
        assertNoEncontrado(bloquear(jugador.getId(), duenoA));
    }

    @Test
    @DisplayName("duenoPropio_idInexistente_Devuelve404Unico")
    void duenoPropio_idInexistente_Devuelve404() throws Exception {
        assertNoEncontrado(bloquear(987654321L, duenoA));
    }

    @Test
    @DisplayName("duenoPropio_usuarioNoJugador_Devuelve404Unico")
    void duenoPropio_usuarioNoJugador_Devuelve404() throws Exception {
        assertNoEncontrado(bloquear(duenoB.getId(), duenoA));
    }

    @Test
    @DisplayName("duenoAjeno_Devuelve403")
    void duenoAjeno_Devuelve403() throws Exception {
        Usuario cliente = jugadorConReservaEnA();
        bloquear(cliente.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        bloquear(jugador.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleado_Devuelve403")
    void empleado_Devuelve403() throws Exception {
        bloquear(jugador.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
