package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractFotoSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/establecimientos/{id}/fotos/{fileId}. @PreAuthorize OWNER/ADMIN
 * (FotoEstablecimientoController:59). FotoEstablecimientoService.borrar autoriza en la fase 1
 * (:165, validarPropietarioOAdmin) y confirma que el fileId es de ESE complejo (:166, buscarFoto ->
 * 404 "Foto no encontrada en este establecimiento"); recién después llama a ImageKit (:172). Sólo se
 * testean los rechazos que cortan antes de ImageKit.
 */
@DisplayName("DELETE /api/v1/establecimientos/{id}/fotos/{fileId}")
class FotoBorrarAutorizacionTest extends AbstractFotoSecurityTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String MENSAJE_FOTO = "Foto no encontrada en este establecimiento";

    private ResultActions borrar(Long id, String fileId, Usuario usuario) throws Exception {
        return mockMvc.perform(delete("/api/v1/establecimientos/" + id + "/fotos/" + fileId)
                .header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("jugador_Devuelve403SinBorrar")
    void jugador_Devuelve403SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_a1", jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403SinBorrar")
    void empleadoConPermiso_Devuelve403SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_a1", empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403SinBorrar")
    void empleadoConTodosLosPermisos_Devuelve403SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_a1", empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjenoSinBorrar")
    void establecimientoInexistente_Devuelve403SinBorrar() throws Exception {
        borrar(999_999L, "file_a1", duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoAjenoConFileIdInexistente_Devuelve403IgualQueConFileIdExistente")
    void duenoAjenoConFileIdInexistente_Devuelve403() throws Exception {
        borrar(establecimientoA.getId(), "file_que_no_existe", duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjeno_Devuelve403SinBorrar")
    void duenoDeOtroEstablecimientoConPathAjeno_Devuelve403SinBorrar() throws Exception {
        // se autoriza antes de buscar la foto y antes de llamar a ImageKit
        borrar(establecimientoA.getId(), "file_a1", duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathPropioYFotoAjena_Devuelve404SinBorrar")
    void duenoDeOtroEstablecimientoConPathPropioYFotoAjena_Devuelve404SinBorrar() throws Exception {
        // Cruce: path de B (autoriza) con el fileId de una foto de A -> FotoEstablecimientoService:166
        borrar(establecimientoB.getId(), "file_a1", duenoB)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(MENSAJE_FOTO));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDelEstablecimientoConFotoDeOtroComplejo_Devuelve404SinBorrar")
    void duenoDelEstablecimientoConFotoDeOtroComplejo_Devuelve404SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_b1", duenoA)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(MENSAJE_FOTO));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("adminConFotoDeOtroComplejo_Devuelve404SinBorrar")
    void adminConFotoDeOtroComplejo_Devuelve404SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_b1", admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(MENSAJE_FOTO));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("fileIdInexistente_Devuelve404SinBorrar")
    void fileIdInexistente_Devuelve404SinBorrar() throws Exception {
        borrar(establecimientoA.getId(), "file_no_existe", duenoA)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value(MENSAJE_FOTO));
        assertFotosIntactas();
    }
}
