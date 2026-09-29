package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/admin/usuarios/{id}: completa la matriz de roles de AdminUsuarioControllerTest
 * (que ya cubre OWNER 403 y el caso ADMIN). Sin @PreAuthorize a propósito (ver pendiente 44):
 * el rol ADMIN lo valida UsuarioEliminacionService.eliminarComoAdmin (línea 89).
 */
@DisplayName("DELETE /api/v1/admin/usuarios/{id} (matriz de roles)")
class AdminUsuarioEliminacionAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403 = "No está autorizado para eliminar cuentas";

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        assertNoEliminado(jugador);
    }

    @Test
    @DisplayName("empleado_Devuelve403YNoElimina")
    void empleado_Devuelve403YNoElimina() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", bearer(empleadoConPermiso)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403));
        assertNoEliminado(jugador);
    }

    @Test
    @DisplayName("jugador_Devuelve403YNoElimina")
    void jugador_Devuelve403YNoElimina() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/usuarios/" + duenoA.getId())
                        .header("Authorization", bearer(jugador)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403));
        assertNoEliminado(duenoA);
    }

    private void assertNoEliminado(Usuario usuario) {
        assertNull(usuarioRepository.findById(usuario.getId()).orElseThrow().getDeletedAt());
    }
}
