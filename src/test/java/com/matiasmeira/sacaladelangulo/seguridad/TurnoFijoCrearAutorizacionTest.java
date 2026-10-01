package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.AbstractTurnoFijoSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/turnos-fijos. @PreAuthorize OWNER/ADMIN (TurnoFijoController:44). El dueño ajeno lo
 * rechaza validarPropietarioOAdmin sobre el establecimiento de la cancha del body
 * (TurnoFijoService:205) como AccessDeniedException -> 403. Cada llamada lleva su propio
 * Idempotency-Key (obligatorio en esta ruta).
 */
@DisplayName("POST /api/v1/turnos-fijos")
class TurnoFijoCrearAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private void assertNadaPersistido() {
        assertEquals(0, turnoFijoRepository.count());
        assertEquals(0, reservaRepository.count());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        postCrear(canchaA, jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaPersistido();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        postCrear(canchaA, empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaPersistido();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        Usuario empleadoTodo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        postCrear(canchaA, empleadoTodo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaPersistido();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // TurnoFijoService:205 (AutorizacionEmpleadoService:135): cancha de A en el body, token de B
        postCrear(canchaA, duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertNadaPersistido();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201YPersisteLaSerie")
    void duenoDelEstablecimiento_Devuelve201YPersisteLaSerie() throws Exception {
        postCrear(canchaA, duenoA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.ocurrencias.length()").value(1));
        assertSerieYReservaCreadas();
    }

    @Test
    @DisplayName("admin_Devuelve201YPersisteLaSerie")
    void admin_Devuelve201YPersisteLaSerie() throws Exception {
        postCrear(canchaA, admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.ocurrencias.length()").value(1));
        assertSerieYReservaCreadas();
    }

    private void assertSerieYReservaCreadas() {
        assertEquals(1, turnoFijoRepository.count());
        assertEquals(1, reservaRepository.count());
        TurnoFijo serie = turnoFijoRepository.findAll().get(0);
        assertEquals(EstadoTurnoFijo.ACTIVO, serie.getEstado());
        assertEquals("Grupo Test", serie.getNombreClienteManual());
        Reserva reserva = reservaRepository.findAll().get(0);
        assertEquals(EstadoReserva.CONFIRMADA, reserva.getEstado());
        assertEquals("Grupo Test", reserva.getNombreClienteManual());
    }
}
