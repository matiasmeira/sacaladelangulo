package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{e}/empleados (listado del panel). @PreAuthorize OWNER/ADMIN
 * (EmpleadoController:46): el empleado queda afuera aunque tenga todos los permisos.
 * EmpleadoService.listarPorEstablecimiento autoriza con validarPropietarioOAdmin (línea 116 ->
 * AutorizacionEmpleadoService:135). Se siembra un empleado extra en B para aseverar que el listado de A no
 * lo incluye. GET /empleados/activos (cookie de dispositivo) está en ModoCajaEmpleadosActivosAutorizacionTest.
 */
@DisplayName("GET /api/v1/establecimientos/{e}/empleados")
class EmpleadoListarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    private ResultActions listar(Usuario quien) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/empleados")
                .header("Authorization", bearer(quien)));
    }

    @Test
    @DisplayName("duenoPropio_Devuelve200SoloConSusEmpleados")
    void duenoPropio() throws Exception {
        empleado(establecimientoB, EnumSet.of(PermisoEmpleado.OPERAR_CAJA));
        listar(duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(
                        empleadoConPermiso.getId().intValue(), empleadoSinPermiso.getId().intValue())))
                .andExpect(jsonPath("$[*].establecimientoId", containsInAnyOrder(
                        establecimientoA.getId().intValue(), establecimientoA.getId().intValue())));
    }

    @Test
    @DisplayName("admin_Devuelve200")
    void admin() throws Exception {
        listar(admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("duenoAjeno_Devuelve403")
    void duenoAjeno() throws Exception {
        listar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador() throws Exception {
        listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403PorAnotacion")
    void empleadoConPermiso() throws Exception {
        listar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
    void empleadoTodos() throws Exception {
        Usuario todo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        listar(todo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
