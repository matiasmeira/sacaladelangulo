package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos (lista propia). @PreAuthorize OWNER/ADMIN (EstablecimientoController:50).
 * El service devuelve sólo los del usuario del token (EstablecimientoService.obtenerMisEstablecimientos:100,
 * findByDuenoIdAndDeletedAtIsNull), también para el ADMIN: no hay vista "todos" en este endpoint.
 */
@DisplayName("GET /api/v1/establecimientos")
class EstablecimientoListarPropiosAutorizacionTest extends AbstractSecurityWebTest {

    private ResultActions listar(Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos").header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        listar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        listar(empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("duenoA_VeSoloSusEstablecimientos")
    void duenoA_VeSoloSusEstablecimientos() throws Exception {
        listar(duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(establecimientoA.getId()));
    }

    @Test
    @DisplayName("duenoB_VeSoloSusEstablecimientos")
    void duenoB_VeSoloSusEstablecimientos() throws Exception {
        listar(duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(establecimientoB.getId()));
    }

    @Test
    @DisplayName("admin_VeSoloLosPropiosYNoLosDeLosDuenos")
    void admin_VeSoloLosPropiosYNoLosDeLosDuenos() throws Exception {
        listar(admin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        Establecimiento delAdmin = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo del admin").slug("complejo-del-admin").dueno(admin)));

        listar(admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(delAdmin.getId()));
    }

    @Test
    @DisplayName("noListaLosEliminados")
    void noListaLosEliminados() throws Exception {
        establecimientoRepository.save(Establecimientos.establecimientoEliminado(b -> b
                .nombre("Complejo viejo").slug("complejo-viejo-eliminado").dueno(duenoA)));

        listar(duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(establecimientoA.getId()));
    }
}
