package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractFotoSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/fotos. @PreAuthorize OWNER/ADMIN (FotoEstablecimientoController:35).
 * FotoEstablecimientoService.listar (:99-102): busca el complejo (404) y valida con
 * validarPropietarioOAdmin (:101) -> 403 al dueño ajeno. No llama a ImageKit.
 */
@DisplayName("GET /api/v1/establecimientos/{id}/fotos")
class FotoListarAutorizacionTest extends AbstractFotoSecurityTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    private ResultActions listar(Long id, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + id + "/fotos").header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // FotoEstablecimientoService:101 -> AutorizacionEmpleadoService:135
        listar(establecimientoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("idInexistente_Devuelve403IgualAlAjeno")
    void idInexistente_Devuelve403() throws Exception {
        listar(999_999L, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("idInexistenteComoAdmin_Devuelve404")
    void idInexistenteComoAdmin_Devuelve404() throws Exception {
        listar(999_999L, admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200ConSusFotosYNoLasAjenas")
    void duenoDelEstablecimiento_Devuelve200ConSusFotosYNoLasAjenas() throws Exception {
        listar(establecimientoA.getId(), duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fileId").value("file_a1"))
                .andExpect(jsonPath("$[1].fileId").value("file_a2"));
    }

    @Test
    @DisplayName("admin_Devuelve200ConLasFotosDelComplejoAjeno")
    void admin_Devuelve200ConLasFotosDelComplejoAjeno() throws Exception {
        listar(establecimientoB.getId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileId").value("file_b1"));
    }
}
