package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractFotoSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{id}/fotos (multipart). @PreAuthorize OWNER/ADMIN
 * (FotoEstablecimientoController:43). FotoEstablecimientoService.subir autoriza en la fase 1
 * (:109-115, validarPropietarioOAdmin en :111) y recién después llama a ImageKit (:118). Sólo se
 * testean los rechazos que cortan antes de ImageKit; el alta exitosa (que sube al servicio externo)
 * la cubre FotoEstablecimientoControllerIntegrationTest con ImageKit mockeado, y no se repite acá
 * para no meter un mock en la base compartida.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/fotos")
class FotoSubirAutorizacionTest extends AbstractFotoSecurityTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    /** Cabecera JPEG válida de 64 bytes: si algo pasara la autorización, la validación del archivo no sería lo que corta. */
    private static byte[] jpeg() {
        byte[] bytes = new byte[64];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private ResultActions subir(Long id, Usuario usuario) throws Exception {
        return mockMvc.perform(multipart("/api/v1/establecimientos/" + id + "/fotos")
                .file(new MockMultipartFile("archivo", "foto.jpg", "image/jpeg", jpeg()))
                .header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("jugador_Devuelve403SinSubir")
    void jugador_Devuelve403SinSubir() throws Exception {
        subir(establecimientoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403SinSubir")
    void empleadoConPermiso_Devuelve403SinSubir() throws Exception {
        subir(establecimientoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403SinSubir")
    void empleadoConTodosLosPermisos_Devuelve403SinSubir() throws Exception {
        subir(establecimientoA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403SinSubir")
    void duenoDeOtroEstablecimiento_Devuelve403SinSubir() throws Exception {
        // FotoEstablecimientoService:111 -> AutorizacionEmpleadoService:135, antes de ImageKit (:118)
        subir(establecimientoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("idInexistente_Devuelve403IgualAlAjenoSinSubir")
    void idInexistente_Devuelve403SinSubir() throws Exception {
        subir(999_999L, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertFotosIntactas();
    }

    @Test
    @DisplayName("idInexistenteComoAdmin_Devuelve404SinSubir")
    void idInexistenteComoAdmin_Devuelve404SinSubir() throws Exception {
        subir(999_999L, admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
        assertFotosIntactas();
    }
}
