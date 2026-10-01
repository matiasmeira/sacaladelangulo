package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.AbstractTurnoFijoSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/v1/turnos-fijos/{id}/cliente. @PreAuthorize OWNER/ADMIN (TurnoFijoController:113). El
 * dueño ajeno lo rechaza validarPropietarioOAdmin (TurnoFijoService:471;
 * AutorizacionEmpleadoService:135) -> 403. Un 200 corrige el nombre de la serie y el de sus
 * reservas futuras vivas (TurnoFijoService:478-498).
 */
@DisplayName("PATCH /api/v1/turnos-fijos/{id}/cliente")
class TurnoFijoEditarClienteAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private TurnoFijo serieA;

    @BeforeEach
    void sembrarSerie() {
        serieA = serieDeA();
    }

    private ResultActions editarCliente(Usuario usuario) throws Exception {
        return mockMvc.perform(patch("/api/v1/turnos-fijos/" + serieA.getId() + "/cliente")
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Cliente Nuevo\",\"telefono\":\"1155\"}"));
    }

    private Reserva reservaDeLaSerie() {
        return reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(serieA.getId()).get(0);
    }

    private void assertClienteSinCambios() {
        assertEquals(NOMBRE_CLIENTE_SERIE, turnoFijoRepository.findById(serieA.getId()).orElseThrow().getNombreClienteManual());
        assertEquals(NOMBRE_CLIENTE_SERIE, reservaDeLaSerie().getNombreClienteManual());
    }

    private void assertClienteCorregido() {
        TurnoFijo serie = turnoFijoRepository.findById(serieA.getId()).orElseThrow();
        assertEquals("Cliente Nuevo", serie.getNombreClienteManual());
        assertEquals("1155", serie.getTelefonoClienteManual());
        assertEquals("Cliente Nuevo", reservaDeLaSerie().getNombreClienteManual());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        editarCliente(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertClienteSinCambios();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        editarCliente(empleadoTodo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertClienteSinCambios();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoCambiaNada")
    void duenoDeOtroEstablecimiento_Devuelve403YNoCambiaNada() throws Exception {
        // TurnoFijoService:471 (AutorizacionEmpleadoService:135)
        editarCliente(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertClienteSinCambios();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YCorrigeSerieYReservaFutura")
    void duenoDelEstablecimiento_Devuelve200YCorrigeSerieYReservaFutura() throws Exception {
        editarCliente(duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreClienteManual").value("Cliente Nuevo"));
        assertClienteCorregido();
    }

    @Test
    @DisplayName("admin_Devuelve200YCorrigeSerieYReservaFutura")
    void admin_Devuelve200YCorrigeSerieYReservaFutura() throws Exception {
        editarCliente(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombreClienteManual").value("Cliente Nuevo"));
        assertClienteCorregido();
    }
}
