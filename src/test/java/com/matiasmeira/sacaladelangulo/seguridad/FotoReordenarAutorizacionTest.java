package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractFotoSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/establecimientos/{id}/fotos/orden. @PreAuthorize OWNER/ADMIN
 * (FotoEstablecimientoController:69). FotoEstablecimientoService.reordenar (:198-247): valida con
 * validarPropietarioOAdmin (:200) y exige que la lista sea exactamente la de las fotos del complejo
 * del path (:219-223, IllegalArgumentException -> 400). No llama a ImageKit, así que el caso OK se
 * prueba completo con efecto persistido.
 */
@DisplayName("PUT /api/v1/establecimientos/{id}/fotos/orden")
class FotoReordenarAutorizacionTest extends AbstractFotoSecurityTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String MENSAJE_LISTA = "La lista de fileIds tiene que contener exactamente las fotos actuales del "
            + "establecimiento, una sola vez cada una.";

    private ResultActions reordenar(Long id, String fileIdsJson, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/establecimientos/" + id + "/fotos/orden")
                .header("Authorization", bearer(usuario))
                .contentType("application/json")
                .content("{\"fileIds\":" + fileIdsJson + "}"));
    }

    private static final String ORDEN_A_INVERTIDO = "[\"file_a2\",\"file_a1\"]";

    @Test
    @DisplayName("jugador_Devuelve403SinReordenar")
    void jugador_Devuelve403SinReordenar() throws Exception {
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403SinReordenar")
    void empleadoConPermiso_Devuelve403SinReordenar() throws Exception {
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403SinReordenar")
    void empleadoConTodosLosPermisos_Devuelve403SinReordenar() throws Exception {
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjenoSinReordenar")
    void establecimientoInexistente_Devuelve403SinReordenar() throws Exception {
        reordenar(999_999L, ORDEN_A_INVERTIDO, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathAjeno_Devuelve403SinReordenar")
    void duenoDeOtroEstablecimientoConPathAjeno_Devuelve403SinReordenar() throws Exception {
        // FotoEstablecimientoService:200 -> AutorizacionEmpleadoService:135
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimientoConPathPropioYFotosAjenas_Devuelve400SinReordenar")
    void duenoDeOtroEstablecimientoConPathPropioYFotosAjenas_Devuelve400SinReordenar() throws Exception {
        // Cruce: path de B (autoriza) con los fileId de A -> FotoEstablecimientoService:219-223
        reordenar(establecimientoB.getId(), "[\"file_a1\",\"file_a2\"]", duenoB)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_LISTA));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDelEstablecimientoConMezclaDeFotosDeOtroComplejo_Devuelve400SinReordenar")
    void duenoDelEstablecimientoConMezclaDeFotosDeOtroComplejo_Devuelve400SinReordenar() throws Exception {
        reordenar(establecimientoA.getId(), "[\"file_b1\",\"file_a1\",\"file_a2\"]", duenoA)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(MENSAJE_LISTA));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YReordenaSoloEseComplejo")
    void duenoDelEstablecimiento_Devuelve200YReordenaSoloEseComplejo() throws Exception {
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value("file_a2"))
                .andExpect(jsonPath("$[1].fileId").value("file_a1"));
        assertEquals(List.of("file_a2", "file_a1"), fileIdsDe(establecimientoA));
        assertEquals(List.of("file_b1"), fileIdsDe(establecimientoB));
    }

    @Test
    @DisplayName("admin_Devuelve200YReordenaElComplejoAjeno")
    void admin_Devuelve200YReordenaElComplejoAjeno() throws Exception {
        reordenar(establecimientoA.getId(), ORDEN_A_INVERTIDO, admin).andExpect(status().isOk());
        assertEquals(List.of("file_a2", "file_a1"), fileIdsDe(establecimientoA));
        assertEquals(List.of("file_b1"), fileIdsDe(establecimientoB));
    }
}
