package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos. @PreAuthorize OWNER/ADMIN (EstablecimientoController:41). El service
 * no tiene chequeo de dueño: el dueño es siempre el usuario del token
 * (EstablecimientoService.crearEstablecimiento:66 y :83). Decisión: un ADMIN puede crear complejos
 * propios. El tope de 3 y las validaciones del body tienen sus propios tests de service.
 */
@DisplayName("POST /api/v1/establecimientos")
class EstablecimientoCrearAutorizacionTest extends AbstractSecurityWebTest {

    private static final String BODY = "{\"nombre\":\"Complejo Nuevo\",\"direccion\":\"Calle 123\","
            + "\"latitud\":-34.6,\"longitud\":-58.4,\"requiereSena\":false,\"requiereTelefonoVerificado\":false}";

    private ResultActions crear(Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/establecimientos")
                .header("Authorization", bearer(usuario))
                .contentType("application/json")
                .content(BODY));
    }

    @Test
    @DisplayName("jugador_Devuelve403SinCrear")
    void jugador_Devuelve403SinCrear() throws Exception {
        crear(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(2, establecimientoRepository.count());
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403SinCrear")
    void empleadoConPermiso_Devuelve403SinCrear() throws Exception {
        crear(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(2, establecimientoRepository.count());
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403SinCrear")
    void empleadoConTodosLosPermisos_Devuelve403SinCrear() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        crear(empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEquals(2, establecimientoRepository.count());
    }

    @Test
    @DisplayName("owner_Devuelve201YQuedaComoDuenoDelComplejo")
    void owner_Devuelve201YQuedaComoDuenoDelComplejo() throws Exception {
        crear(duenoA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.duenoId").value(duenoA.getId()))
                .andExpect(jsonPath("$.nombre").value("Complejo Nuevo"));

        List<Establecimiento> deA = establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(duenoA.getId());
        assertEquals(2, deA.size());
        assertEquals(1, deA.stream().filter(e -> "Complejo Nuevo".equals(e.getNombre())).count());
        assertEquals(1, establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(duenoB.getId()).size());
    }

    @Test
    @DisplayName("admin_Devuelve201YQuedaComoDuenoDelComplejo")
    void admin_Devuelve201YQuedaComoDuenoDelComplejo() throws Exception {
        crear(admin).andExpect(status().isCreated()).andExpect(jsonPath("$.duenoId").value(admin.getId()));

        List<Establecimiento> delAdmin = establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(admin.getId());
        assertEquals(1, delAdmin.size());
        assertEquals("Complejo Nuevo", delAdmin.get(0).getNombre());
        assertEquals(1, establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(duenoA.getId()).size());
        assertEquals(1, establecimientoRepository.findByDuenoIdAndDeletedAtIsNull(duenoB.getId()).size());
    }
}
