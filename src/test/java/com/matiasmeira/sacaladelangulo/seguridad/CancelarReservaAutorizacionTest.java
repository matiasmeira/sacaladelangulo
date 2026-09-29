package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/reservas/{id}/cancelar. @PreAuthorize PLAYER/OWNER/ADMIN/EMPLOYEE
 * (ReservaController:89). El chequeo fino es inline en ReservaService.cancelarReserva
 * (líneas 599-615): pasa el admin, el dueño del establecimiento, el jugador de la reserva o un
 * empleado del establecimiento con CANCELAR_RESERVA; el resto recibe AccessDeniedException
 * (línea 614) -> 403. Reserva futura (dentro del plazo de cancelación del jugador).
 */
@DisplayName("PUT /api/v1/reservas/{id}/cancelar")
class CancelarReservaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No está autorizado para cancelar esta reserva";

    @Autowired
    private ReservaRepository reservaRepository;

    private Reserva reserva;

    @BeforeEach
    void crearReservaFutura() {
        reserva = reservaFutura();
    }

    private Reserva reservaFutura() {
        LocalDateTime inicio = LocalDateTime.now().plusDays(3).withHour(18).withMinute(0).withSecond(0).withNano(0);
        return reservaRepository.save(Reserva.builder()
                .cancha(canchaA)
                .jugador(jugador)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(90))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO)
                .build());
    }

    private ResultActions cancelar(Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/reservas/" + reserva.getId() + "/cancelar")
                .header("Authorization", bearer(usuario)));
    }

    private void assertEstado(EstadoReserva esperado) {
        assertEquals(esperado, reservaRepository.findById(reserva.getId()).orElseThrow().getEstado());
    }

    private Usuario otroJugador() {
        return usuarioRepository.save(Usuario.builder()
                .email("otro-jugador-" + System.nanoTime() + "@seguridad-http-test.com")
                .password("hash")
                .nombre("Otro jugador")
                .rol(Role.PLAYER)
                .permisos(new java.util.HashSet<>())
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
    }

    @Test
    @DisplayName("jugadorDeLaReserva_Devuelve200YCancela")
    void jugadorDeLaReserva_Devuelve200YCancela() throws Exception {
        cancelar(jugador).andExpect(status().isOk());
        assertEstado(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("otroJugador_Devuelve403")
    void otroJugador_Devuelve403() throws Exception {
        // ReservaService:614
        cancelar(otroJugador()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // ReservaService:614
        cancelar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("empleadoSinCancelarReserva_Devuelve403")
    void empleadoSinCancelarReserva_Devuelve403() throws Exception {
        // ReservaService:614 (sin permisos, y OPERAR_CAJA tampoco habilita cancelar)
        cancelar(empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
        cancelar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConCancelarReserva_Devuelve403")
    void empleadoDeOtroEstablecimientoConCancelarReserva_Devuelve403() throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(PermisoEmpleado.CANCELAR_RESERVA));
        // ReservaService:614; el permiso vale sólo en el establecimiento del empleado (AutorizacionEmpleadoService:117-123)
        cancelar(empleadoDeB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("empleadoConCancelarReserva_Devuelve200YCancela")
    void empleadoConCancelarReserva_Devuelve200YCancela() throws Exception {
        Usuario empleadoDeA = empleado(establecimientoA, Set.of(PermisoEmpleado.CANCELAR_RESERVA));
        cancelar(empleadoDeA).andExpect(status().isOk());
        assertEstado(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YCancela")
    void duenoDelEstablecimiento_Devuelve200YCancela() throws Exception {
        cancelar(duenoA).andExpect(status().isOk());
        assertEstado(EstadoReserva.CANCELADA);
    }

    @Test
    @DisplayName("admin_Devuelve200YCancela")
    void admin_Devuelve200YCancela() throws Exception {
        cancelar(admin).andExpect(status().isOk());
        assertEstado(EstadoReserva.CANCELADA);
    }
}
