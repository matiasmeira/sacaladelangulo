package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /empleados/{id}/pin, PUT /empleados/{id}/permisos y DELETE /empleados/{id} bajo
 * /api/v1/establecimientos/{establecimientoId}. @PreAuthorize OWNER/ADMIN (EmpleadoController:71,
 * :82, :93): jugador y empleado (aun con todos los permisos) quedan afuera ahí.
 *
 * <p>Cruce de establecimiento (IDOR): EmpleadoService busca al empleado con
 * buscarEmpleadoDelEstablecimiento (línea 177), que exige que pertenezca al establecimiento del
 * PATH (IllegalArgumentException -> 400), y valida al actor contra empleado.getEstablecimiento()
 * (líneas 136, 149, 166), no contra el path: un dueño B que usa su propio establecimientoId con
 * el empleadoId de A no llega a tocar al empleado. Un dueño B con el path correcto de A lo
 * rechaza validarPropietarioOAdmin (AutorizacionEmpleadoService:135) con 403.
 */
@DisplayName("Administración de empleados (PIN, permisos y baja)")
class EmpleadoAdminAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String MENSAJE_EMPLEADO_AJENO = "El empleado no pertenece a este establecimiento";
    private static final String PIN_NUEVO = "4827";

    private String rutaBase(Long establecimientoId, Usuario empleado) {
        return "/api/v1/establecimientos/" + establecimientoId + "/empleados/" + empleado.getId();
    }

    private Usuario recargar(Usuario u) {
        return usuarioRepository.findById(u.getId()).orElseThrow();
    }

    // ---------------------------------------------------------------- PIN

    @Nested
    @DisplayName("PUT /{empleadoId}/pin")
    class CambiarPin {

        private ResultActions cambiar(Usuario actor, Long establecimientoId, Usuario empleado) throws Exception {
            return mockMvc.perform(put(rutaBase(establecimientoId, empleado) + "/pin")
                    .header("Authorization", bearer(actor))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pin\":\"" + PIN_NUEVO + "\"}"));
        }

        private void assertPinSinCambios(Usuario empleado) {
            Usuario actual = recargar(empleado);
            assertEquals(empleado.getPassword(), actual.getPassword());
            assertEquals(empleado.getTokenVersion(), actual.getTokenVersion());
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            cambiar(jugador, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertPinSinCambios(empleadoSinPermiso);
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoConTodosLosPermisos_Devuelve403PorAnotacion() throws Exception {
            Usuario todopoderoso = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
            cambiar(todopoderoso, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertPinSinCambios(empleadoSinPermiso);
        }

        @Test
        @DisplayName("duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoCambiaElPin")
        void duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoCambiaElPin() throws Exception {
            // EmpleadoService:177 (buscarEmpleadoDelEstablecimiento) -> 400
            cambiar(duenoB, establecimientoB.getId(), empleadoSinPermiso)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_EMPLEADO_AJENO));
            assertPinSinCambios(empleadoSinPermiso);
        }

        @Test
        @DisplayName("duenoBConElPathDeA_Devuelve403YNoCambiaElPin")
        void duenoBConElPathDeA_Devuelve403YNoCambiaElPin() throws Exception {
            // EmpleadoService:149 -> AutorizacionEmpleadoService:135
            cambiar(duenoB, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertPinSinCambios(empleadoSinPermiso);
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve200YCambiaElHashDelPin")
        void duenoDelEstablecimiento_Devuelve200YCambiaElHashDelPin() throws Exception {
            cambiar(duenoA, establecimientoA.getId(), empleadoSinPermiso).andExpect(status().isOk());
            Usuario actual = recargar(empleadoSinPermiso);
            assertNotEquals(empleadoSinPermiso.getPassword(), actual.getPassword());
            assertEquals(empleadoSinPermiso.getTokenVersion() + 1, actual.getTokenVersion());
        }
    }

    // ------------------------------------------------------------ Permisos

    @Nested
    @DisplayName("PUT /{empleadoId}/permisos")
    class ActualizarPermisos {

        private static final String BODY = "{\"permisos\":[\"FINALIZAR_RESERVA\",\"CANCELAR_RESERVA\"]}";

        private ResultActions actualizar(Usuario actor, Long establecimientoId, Usuario empleado) throws Exception {
            return mockMvc.perform(put(rutaBase(establecimientoId, empleado) + "/permisos")
                    .header("Authorization", bearer(actor))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY));
        }

        private void assertPermisosOriginales() {
            assertEquals(Set.of(PermisoEmpleado.OPERAR_CAJA), permisosPersistidos(empleadoConPermiso));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            actualizar(jugador, establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertPermisosOriginales();
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoConTodosLosPermisos_Devuelve403PorAnotacion() throws Exception {
            Usuario todopoderoso = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
            actualizar(todopoderoso, establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertPermisosOriginales();
        }

        @Test
        @DisplayName("duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoCambiaLosPermisos")
        void duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoCambiaLosPermisos() throws Exception {
            // EmpleadoService:177 (buscarEmpleadoDelEstablecimiento) -> 400
            actualizar(duenoB, establecimientoB.getId(), empleadoConPermiso)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_EMPLEADO_AJENO));
            assertPermisosOriginales();
        }

        @Test
        @DisplayName("duenoBConElPathDeA_Devuelve403YNoCambiaLosPermisos")
        void duenoBConElPathDeA_Devuelve403YNoCambiaLosPermisos() throws Exception {
            // EmpleadoService:136 -> AutorizacionEmpleadoService:135
            actualizar(duenoB, establecimientoA.getId(), empleadoConPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertPermisosOriginales();
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve200YDejaLosPermisos")
        void duenoDelEstablecimiento_Devuelve200YDejaLosPermisos() throws Exception {
            actualizar(duenoA, establecimientoA.getId(), empleadoConPermiso).andExpect(status().isOk());
            assertEquals(Set.of(PermisoEmpleado.FINALIZAR_RESERVA, PermisoEmpleado.CANCELAR_RESERVA),
                    permisosPersistidos(empleadoConPermiso));
        }
    }

    // ---------------------------------------------------------------- Baja

    @Nested
    @DisplayName("DELETE /{empleadoId}")
    class Desactivar {

        private ResultActions desactivar(Usuario actor, Long establecimientoId, Usuario empleado) throws Exception {
            return mockMvc.perform(delete(rutaBase(establecimientoId, empleado))
                    .header("Authorization", bearer(actor)));
        }

        private void assertActivo(boolean esperado) {
            assertEquals(esperado, recargar(empleadoSinPermiso).getIsActive());
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            desactivar(jugador, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertActivo(true);
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoConTodosLosPermisos_Devuelve403PorAnotacion() throws Exception {
            Usuario todopoderoso = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
            desactivar(todopoderoso, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertActivo(true);
        }

        @Test
        @DisplayName("duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoLoDesactiva")
        void duenoBConSuEstablecimientoEnElPathYElEmpleadoDeA_RechazaYNoLoDesactiva() throws Exception {
            // EmpleadoService:177 (buscarEmpleadoDelEstablecimiento) -> 400
            desactivar(duenoB, establecimientoB.getId(), empleadoSinPermiso)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_EMPLEADO_AJENO));
            assertActivo(true);
        }

        @Test
        @DisplayName("duenoBConElPathDeA_Devuelve403YNoLoDesactiva")
        void duenoBConElPathDeA_Devuelve403YNoLoDesactiva() throws Exception {
            // EmpleadoService:166 -> AutorizacionEmpleadoService:135
            desactivar(duenoB, establecimientoA.getId(), empleadoSinPermiso)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertActivo(true);
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve204YDesactiva")
        void duenoDelEstablecimiento_Devuelve204YDesactiva() throws Exception {
            desactivar(duenoA, establecimientoA.getId(), empleadoSinPermiso).andExpect(status().isNoContent());
            assertActivo(false);
            assertTrue(usuarioRepository.findById(empleadoSinPermiso.getId()).isPresent());
        }
    }
}
