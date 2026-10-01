package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.AbstractTurnoFijoSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/turnos-fijos/{id}/renovar. @PreAuthorize OWNER/ADMIN (TurnoFijoController:100). El
 * dueño ajeno lo rechaza validarPropietarioOAdmin (TurnoFijoService:128;
 * AutorizacionEmpleadoService:135) -> 403. Esta ruta no pide Idempotency-Key. Una serie se
 * renueva una sola vez (TurnoFijoService:137), así que cada test renueva a lo sumo una vez; no se
 * asevera la cantidad exacta de reservas nuevas porque depende del año y del día de la semana.
 */
@DisplayName("POST /api/v1/turnos-fijos/{id}/renovar")
class TurnoFijoRenovarAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private TurnoFijo serieA;

    @BeforeEach
    void sembrarSerie() {
        serieA = serieDeA();
    }

    private ResultActions renovar(Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/turnos-fijos/" + serieA.getId() + "/renovar")
                .header("Authorization", bearer(usuario)));
    }

    private void assertNadaRenovado() {
        assertEquals(1, turnoFijoRepository.count());
        assertEquals(1, reservaRepository.count());
    }

    private void assertRenovada() {
        assertEquals(2, turnoFijoRepository.count());
        TurnoFijo nueva = turnoFijoRepository.findAll().stream()
                .filter(t -> !t.getId().equals(serieA.getId()))
                .findFirst().orElseThrow();
        assertEquals(serieA.getId(), nueva.getRenovadoDesdeId());
        List<Reserva> reservasNuevas = reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(nueva.getId());
        assertTrue(reservasNuevas.size() > 1, "la serie renovada tiene que traer las ocurrencias del año siguiente");
        assertTrue(reservasNuevas.stream().allMatch(r -> r.getEstado() == EstadoReserva.CONFIRMADA));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        renovar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaRenovado();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        renovar(empleadoTodo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaRenovado();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoRenueva")
    void duenoDeOtroEstablecimiento_Devuelve403YNoRenueva() throws Exception {
        // TurnoFijoService:128 (AutorizacionEmpleadoService:135)
        renovar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertNadaRenovado();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201YCreaLaSerieDelAnioSiguiente")
    void duenoDelEstablecimiento_Devuelve201YCreaLaSerieDelAnioSiguiente() throws Exception {
        renovar(duenoA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renovadoDesdeId").value(serieA.getId()))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
        assertRenovada();
    }

    @Test
    @DisplayName("admin_Devuelve201YCreaLaSerieDelAnioSiguiente")
    void admin_Devuelve201YCreaLaSerieDelAnioSiguiente() throws Exception {
        renovar(admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renovadoDesdeId").value(serieA.getId()))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
        assertRenovada();
    }
}
