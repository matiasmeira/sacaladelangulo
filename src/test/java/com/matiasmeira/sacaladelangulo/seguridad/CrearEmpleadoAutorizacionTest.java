package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/empleados: alta de empleados con permisos, o sea el punto
 * de escalada de privilegios (un empleado que pudiera crear empleados se daría permisos a sí
 * mismo). @PreAuthorize OWNER/ADMIN (EmpleadoController:36); el dueño ajeno lo rechaza
 * AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135), invocado desde
 * EmpleadoService.crearEmpleado (línea 66), como AccessDeniedException -> 403.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/empleados")
class CrearEmpleadoAutorizacionTest extends AbstractSecurityWebTest {

    private ResultActions crear(String authorization) throws Exception {
        var req = post("/api/v1/establecimientos/" + establecimientoA.getId() + "/empleados")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Nuevo Empleado\",\"pin\":\"4827\",\"permisos\":[\"OPERAR_CAJA\"]}");
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        return mockMvc.perform(req);
    }

    /** Los dos empleados sembrados por el escenario, más los que haya creado el test. */
    private int empleadosDeA() {
        return usuarioRepository.findByEstablecimientoIdAndRol(establecimientoA.getId(), Role.EMPLOYEE).size();
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        crear(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertEquals(2, empleadosDeA());
    }

    @Test
    @DisplayName("empleadoConPermisos_Devuelve403YNoCreaNada")
    void empleadoConPermisos_Devuelve403YNoCreaNada() throws Exception {
        crear(bearer(empleadoConPermiso)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(2, empleadosDeA());
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403YNoCreaNada")
    void duenoDeOtroEstablecimiento_Devuelve403YNoCreaNada() throws Exception {
        crear(bearer(duenoB))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
        assertEquals(2, empleadosDeA());
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201YCreaElEmpleado")
    void duenoDelEstablecimiento_Devuelve201YCreaElEmpleado() throws Exception {
        crear(bearer(duenoA)).andExpect(status().isCreated());
        assertEquals(3, empleadosDeA());
    }
}
