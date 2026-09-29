package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/reservas/{id}/confirmar. @PreAuthorize OWNER/ADMIN (ReservaController:80); el
 * dueño ajeno lo rechaza AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135),
 * invocado desde ReservaService.confirmarReserva (línea 557), como AccessDeniedException -> 403.
 */
@DisplayName("PUT /api/v1/reservas/{id}/confirmar")
class ConfirmarReservaAutorizacionTest extends AbstractSecurityWebTest {

    @Autowired
    private ReservaRepository reservaRepository;

    private Reserva reserva;

    @BeforeEach
    void crearReservaPendienteDeSena() {
        LocalDateTime inicio = LocalDateTime.now().plusDays(2).withHour(18).withMinute(0).withSecond(0).withNano(0);
        reserva = reservaRepository.save(Reserva.builder()
                .cancha(canchaA)
                .jugador(jugador)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(90))
                .estado(EstadoReserva.PENDIENTE_SENA)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO)
                .expiraEn(LocalDateTime.now().plusMinutes(10))
                .build());
    }

    private ResultActions confirmar(String authorization) throws Exception {
        var req = put("/api/v1/reservas/" + reserva.getId() + "/confirmar");
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        return mockMvc.perform(req);
    }

    private void assertEstado(EstadoReserva esperado) {
        assertEquals(esperado, reservaRepository.findById(reserva.getId()).orElseThrow().getEstado());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        confirmar(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertEstado(EstadoReserva.PENDIENTE_SENA);
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        confirmar(bearer(jugador)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEstado(EstadoReserva.PENDIENTE_SENA);
    }

    @Test
    @DisplayName("empleado_Devuelve403")
    void empleado_Devuelve403() throws Exception {
        confirmar(bearer(empleadoConPermiso)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEstado(EstadoReserva.PENDIENTE_SENA);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        confirmar(bearer(duenoB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
        assertEstado(EstadoReserva.PENDIENTE_SENA);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YConfirma")
    void duenoDelEstablecimiento_Devuelve200YConfirma() throws Exception {
        confirmar(bearer(duenoA)).andExpect(status().isOk());
        assertEstado(EstadoReserva.CONFIRMADA);
    }
}
