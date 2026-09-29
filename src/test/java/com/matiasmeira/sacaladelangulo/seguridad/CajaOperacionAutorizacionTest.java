package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.EstadoTurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.model.TurnoCaja;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.MovimientoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/caja/abrir y /caja/movimientos. @PreAuthorize
 * OWNER/ADMIN/EMPLOYEE (CierreCajaController:44 y :62); el jugador queda afuera ahí. El resto lo
 * decide AutorizacionEmpleadoService.validarAccion (línea 59) con OPERAR_CAJA, invocado desde
 * TurnoCajaService.abrirCaja (línea 78) y registrarMovimientoManual (línea 204): dueño ajeno y
 * empleado sin el permiso reciben AccessDeniedException -> 403.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/caja/abrir y /caja/movimientos")
class CajaOperacionAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado para realizar esta acción en este establecimiento";

    @Autowired
    private TurnoCajaRepository turnoCajaRepository;

    @Autowired
    private MovimientoCajaRepository movimientoCajaRepository;

    private void sembrarTurnoAbierto() {
        turnoCajaRepository.save(TurnoCaja.builder()
                .establecimiento(establecimientoA)
                .usuarioApertura(duenoA)
                .fechaApertura(LocalDateTime.now().minusHours(1))
                .fondoInicial(new BigDecimal("1000"))
                .estado(EstadoTurnoCaja.ABIERTO)
                .build());
    }

    @Nested
    @DisplayName("POST /caja/abrir")
    class Abrir {

        private ResultActions abrir(Usuario usuario) throws Exception {
            return mockMvc.perform(post("/api/v1/establecimientos/" + establecimientoA.getId() + "/caja/abrir")
                    .header("Authorization", bearer(usuario))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fondoInicial\":1000}"));
        }

        private void assertTurnosAbiertos(int esperados) {
            assertEquals(esperados, turnoCajaRepository.findAll().stream()
                    .filter(t -> t.getEstado() == EstadoTurnoCaja.ABIERTO).count());
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            abrir(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertTurnosAbiertos(0);
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
        void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
            // AutorizacionEmpleadoService:59
            abrir(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertTurnosAbiertos(0);
        }

        @Test
        @DisplayName("empleadoSinOperarCaja_Devuelve403")
        void empleadoSinOperarCaja_Devuelve403() throws Exception {
            // AutorizacionEmpleadoService:59
            abrir(empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertTurnosAbiertos(0);
        }

        @Test
        @DisplayName("empleadoConOperarCaja_Devuelve201YAbreElTurno")
        void empleadoConOperarCaja_Devuelve201YAbreElTurno() throws Exception {
            abrir(empleadoConPermiso).andExpect(status().isCreated());
            assertTurnosAbiertos(1);
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve201YAbreElTurno")
        void duenoDelEstablecimiento_Devuelve201YAbreElTurno() throws Exception {
            abrir(duenoA).andExpect(status().isCreated());
            assertTurnosAbiertos(1);
        }
    }

    @Nested
    @DisplayName("POST /caja/movimientos")
    class Movimientos {

        private ResultActions registrar(Usuario usuario) throws Exception {
            return mockMvc.perform(post("/api/v1/establecimientos/" + establecimientoA.getId() + "/caja/movimientos")
                    .header("Authorization", bearer(usuario))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tipo\":\"INGRESO\",\"monto\":500,\"descripcion\":\"Movimiento de prueba\"}"));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            sembrarTurnoAbierto();
            registrar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertEquals(0, movimientoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
        void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
            sembrarTurnoAbierto();
            // AutorizacionEmpleadoService:59
            registrar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertEquals(0, movimientoCajaRepository.count());
        }

        @Test
        @DisplayName("empleadoSinOperarCaja_Devuelve403")
        void empleadoSinOperarCaja_Devuelve403() throws Exception {
            sembrarTurnoAbierto();
            // AutorizacionEmpleadoService:59
            registrar(empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertEquals(0, movimientoCajaRepository.count());
        }

        @Test
        @DisplayName("empleadoConOperarCaja_Devuelve201YRegistraElMovimiento")
        void empleadoConOperarCaja_Devuelve201YRegistraElMovimiento() throws Exception {
            sembrarTurnoAbierto();
            registrar(empleadoConPermiso).andExpect(status().isCreated());
            assertEquals(1, movimientoCajaRepository.count());
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve201YRegistraElMovimiento")
        void duenoDelEstablecimiento_Devuelve201YRegistraElMovimiento() throws Exception {
            sembrarTurnoAbierto();
            registrar(duenoA).andExpect(status().isCreated());
            assertEquals(1, movimientoCajaRepository.count());
        }
    }
}
