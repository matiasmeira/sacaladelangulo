package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/establecimientos/{id}. @PreAuthorize OWNER/ADMIN (EstablecimientoController:58). El
 * service busca el complejo (EstablecimientoService:105, 404 "Establecimiento no encontrado") y
 * valida con validarPropietarioOAdmin (:106; AutorizacionEmpleadoService:130-137): el dueño ajeno da
 * AccessDeniedException -> 403 y el ADMIN pasa sobre cualquier complejo (criterio de ese método).
 */
@DisplayName("PUT /api/v1/establecimientos/{id}")
class EstablecimientoActualizarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String BODY = "{\"nombre\":\"Nombre Editado\",\"direccion\":\"Direccion Editada 9\","
            + "\"latitud\":-34.7,\"longitud\":-58.5,\"requiereSena\":false,\"requiereTelefonoVerificado\":false}";

    private ResultActions actualizar(Long id, Usuario usuario) throws Exception {
        return mockMvc.perform(put("/api/v1/establecimientos/" + id)
                .header("Authorization", bearer(usuario))
                .contentType("application/json")
                .content(BODY));
    }

    private Establecimiento recargar(Establecimiento e) {
        return establecimientoRepository.findById(e.getId()).orElseThrow();
    }

    private void assertSinCambios() {
        assertEquals("Complejo A", recargar(establecimientoA).getNombre());
        assertEquals("Complejo B", recargar(establecimientoB).getNombre());
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403")
    void empleadoConPermiso_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403")
    void empleadoConTodosLosPermisos_Devuelve403() throws Exception {
        actualizar(establecimientoA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403SinCambios")
    void duenoDeOtroEstablecimiento_Devuelve403SinCambios() throws Exception {
        // EstablecimientoService:106 -> AutorizacionEmpleadoService:135
        actualizar(establecimientoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("idInexistente_Devuelve403IgualAlAjeno")
    void idInexistente_Devuelve403() throws Exception {
        // Sin oráculo de existencia (pendiente 69): mismo 403 y mismo mensaje que ante un complejo ajeno
        actualizar(999_999L, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertSinCambios();
    }

    @Test
    @DisplayName("idInexistenteComoAdmin_Devuelve404")
    void idInexistenteComoAdmin_Devuelve404() throws Exception {
        actualizar(999_999L, admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
        assertSinCambios();
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YActualizaSoloEseComplejo")
    void duenoDelEstablecimiento_Devuelve200YActualizaSoloEseComplejo() throws Exception {
        actualizar(establecimientoA.getId(), duenoA)
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Nombre Editado"));
        assertEquals("Nombre Editado", recargar(establecimientoA).getNombre());
        assertEquals("Direccion Editada 9", recargar(establecimientoA).getDireccion());
        assertEquals("Complejo B", recargar(establecimientoB).getNombre());
    }

    @Test
    @DisplayName("admin_Devuelve200YActualizaElComplejoAjeno")
    void admin_Devuelve200YActualizaElComplejoAjeno() throws Exception {
        actualizar(establecimientoA.getId(), admin).andExpect(status().isOk());
        assertEquals("Nombre Editado", recargar(establecimientoA).getNombre());
        assertEquals(duenoA.getId(), recargar(establecimientoA).getDueno().getId());
        assertEquals("Complejo B", recargar(establecimientoB).getNombre());
    }
}
