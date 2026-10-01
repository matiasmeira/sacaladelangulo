package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.AbstractTurnoFijoSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/turnos-fijos/{id}/cancelar. @PreAuthorize OWNER/ADMIN (TurnoFijoController:85):
 * un empleado no puede dar de baja una serie ni con permisos operativos de reserva, aunque esos
 * mismos permisos le habiliten leerla. El dueño ajeno lo rechaza validarPropietarioOAdmin
 * (TurnoFijoService:415; AutorizacionEmpleadoService:135) -> 403.
 */
@DisplayName("POST /api/v1/turnos-fijos/{id}/cancelar")
class TurnoFijoCancelarAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private TurnoFijo serieA;

    @BeforeEach
    void sembrarSerie() {
        serieA = serieDeA();
    }

    private ResultActions cancelar(Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/turnos-fijos/" + serieA.getId() + "/cancelar")
                .header("Authorization", bearer(usuario)));
    }

    private Reserva reservaDeLaSerie() {
        return reservaRepository.findByTurnoFijoIdOrderByFechaHoraInicioAsc(serieA.getId()).get(0);
    }

    private void assertSerieIntacta() {
        TurnoFijo serie = turnoFijoRepository.findById(serieA.getId()).orElseThrow();
        assertEquals(EstadoTurnoFijo.ACTIVO, serie.getEstado());
        assertEquals(null, serie.getCanceladoDesde());
        assertEquals(EstadoReserva.CONFIRMADA, reservaDeLaSerie().getEstado());
    }

    private void assertSerieCancelada(LocalDate hoy) {
        TurnoFijo serie = turnoFijoRepository.findById(serieA.getId()).orElseThrow();
        assertEquals(EstadoTurnoFijo.CANCELADO, serie.getEstado());
        assertEquals(hoy, serie.getCanceladoDesde());
        assertEquals(EstadoReserva.CANCELADA, reservaDeLaSerie().getEstado());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        cancelar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSerieIntacta();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Incluye los cuatro permisos que le permiten leer la serie: lo que corta es el rol.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        cancelar(empleadoTodo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSerieIntacta();
    }

    @Test
    @DisplayName("empleadoConPermisoOperativo_Devuelve403")
    void empleadoConPermisoOperativo_Devuelve403() throws Exception {
        Usuario empleadoOperativo = empleado(establecimientoA, Set.of(PermisoEmpleado.FINALIZAR_RESERVA));
        cancelar(empleadoOperativo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSerieIntacta();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoCancelaNada")
    void duenoDeOtroEstablecimiento_Devuelve403YNoCancelaNada() throws Exception {
        // TurnoFijoService:415 (AutorizacionEmpleadoService:135)
        cancelar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertSerieIntacta();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YCancelaLaSerie")
    void duenoDelEstablecimiento_Devuelve200YCancelaLaSerie() throws Exception {
        LocalDate hoy = LocalDate.now();
        cancelar(duenoA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceladas").value(1))
                .andExpect(jsonPath("$.omitidas.length()").value(0));
        assertSerieCancelada(hoy);
    }

    @Test
    @DisplayName("admin_Devuelve200YCancelaLaSerie")
    void admin_Devuelve200YCancelaLaSerie() throws Exception {
        LocalDate hoy = LocalDate.now();
        cancelar(admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canceladas").value(1))
                .andExpect(jsonPath("$.omitidas.length()").value(0));
        assertSerieCancelada(hoy);
    }
}
