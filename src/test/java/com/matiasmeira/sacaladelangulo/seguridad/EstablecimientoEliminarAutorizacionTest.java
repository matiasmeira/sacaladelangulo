package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/v1/establecimientos/{id}. @PreAuthorize hasRole('OWNER') puro (EstablecimientoController:117):
 * el ADMIN no entra ni sobre un complejo propio. El service (EstablecimientoEliminacionService:51) usa
 * validarPropietario -> 403 al dueño ajeno. La baja es lógica (deletedAt + slug renombrado, :78-80) y
 * exige el complejo deshabilitado (:53) y sin reservas futuras (:59-61); esas precondiciones se
 * siembran acá, sus mensajes los cubre el test de service. Los complejos A y B quedan deshabilitados
 * en @BeforeEach para que un rechazo de autorización nunca se confunda con una precondición.
 */
@DisplayName("DELETE /api/v1/establecimientos/{id}")
class EstablecimientoEliminarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    private String slugA;
    private String slugB;

    @BeforeEach
    void deshabilitarComplejos() {
        establecimientoA.setIsActive(false);
        establecimientoB.setIsActive(false);
        establecimientoA = establecimientoRepository.save(establecimientoA);
        establecimientoB = establecimientoRepository.save(establecimientoB);
        slugA = establecimientoA.getSlug();
        slugB = establecimientoB.getSlug();
    }

    private ResultActions eliminar(Long id, Usuario usuario) throws Exception {
        return mockMvc.perform(delete("/api/v1/establecimientos/" + id).header("Authorization", bearer(usuario)));
    }

    private Establecimiento recargar(Establecimiento e) {
        return establecimientoRepository.findById(e.getId()).orElseThrow();
    }

    private void assertNadaEliminado() {
        assertNull(recargar(establecimientoA).getDeletedAt());
        assertNull(recargar(establecimientoB).getDeletedAt());
        assertEquals(slugA, recargar(establecimientoA).getSlug());
        assertEquals(slugB, recargar(establecimientoB).getSlug());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        eliminar(establecimientoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaEliminado();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        eliminar(establecimientoA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaEliminado();
    }

    @Test
    @DisplayName("admin_Devuelve403PorLaAnotacionSobreComplejoAjeno")
    void admin_Devuelve403PorLaAnotacionSobreComplejoAjeno() throws Exception {
        eliminar(establecimientoA.getId(), admin)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaEliminado();
    }

    @Test
    @DisplayName("admin_Devuelve403PorLaAnotacionAunSobreUnComplejoPropio")
    void admin_Devuelve403PorLaAnotacionAunSobreUnComplejoPropio() throws Exception {
        Establecimiento delAdmin = establecimientoRepository.save(Establecimientos.establecimientoDeshabilitado(b -> b
                .nombre("Complejo del admin").slug("complejo-del-admin").dueno(admin)));

        eliminar(delAdmin.getId(), admin)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNull(recargar(delAdmin).getDeletedAt());
        assertNadaEliminado();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403SinEliminar")
    void duenoDeOtroEstablecimiento_Devuelve403SinEliminar() throws Exception {
        // EstablecimientoEliminacionService:51 -> AutorizacionEmpleadoService:160-161
        eliminar(establecimientoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertNadaEliminado();
    }

    @Test
    @DisplayName("idInexistente_Devuelve403IgualAlAjeno")
    void idInexistente_Devuelve403() throws Exception {
        // Sin oráculo de existencia (pendiente 69): mismo 403 y mismo mensaje que ante un complejo ajeno
        eliminar(999_999L, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertNadaEliminado();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve204YDaDeBajaSoloEseComplejo")
    void duenoDelEstablecimiento_Devuelve204YDaDeBajaSoloEseComplejo() throws Exception {
        eliminar(establecimientoA.getId(), duenoA).andExpect(status().isNoContent());

        assertNotNull(recargar(establecimientoA).getDeletedAt());
        assertEquals(slugA + "-eliminado-" + establecimientoA.getId(), recargar(establecimientoA).getSlug());
        assertNull(recargar(establecimientoB).getDeletedAt());
        assertEquals(slugB, recargar(establecimientoB).getSlug());
        assertEquals(2, establecimientoRepository.count());
    }
}
