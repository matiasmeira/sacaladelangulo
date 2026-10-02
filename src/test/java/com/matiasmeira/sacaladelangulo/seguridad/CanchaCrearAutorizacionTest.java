package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/establecimientos/{establecimientoId}/canchas. @PreAuthorize OWNER/ADMIN
 * (CanchaController:34). CanchaService.crearCancha busca el complejo (:58, 404) y valida con
 * validarPropietarioOAdmin (:59; AutorizacionEmpleadoService:130-137): dueño ajeno -> 403, ADMIN pasa.
 * Las reglas de negocio (tarifas, seña, pool) tienen sus propios tests de service.
 */
@DisplayName("POST /api/v1/establecimientos/{id}/canchas")
class CanchaCrearAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";
    private static final String BODY = "{\"nombre\":\"Cancha Nueva\",\"deportes\":[\"PADEL\"],\"precioBase\":1500}";

    private ResultActions crear(Long establecimientoId, Usuario usuario) throws Exception {
        return mockMvc.perform(post("/api/v1/establecimientos/" + establecimientoId + "/canchas")
                .header("Authorization", bearer(usuario))
                .contentType("application/json")
                .content(BODY));
    }

    private void assertCantidades(int enA, int enB) {
        assertEquals(enA, canchaRepository.findByEstablecimientoId(establecimientoA.getId()).size());
        assertEquals(enB, canchaRepository.findByEstablecimientoId(establecimientoB.getId()).size());
    }

    @Test
    @DisplayName("jugador_Devuelve403SinCrear")
    void jugador_Devuelve403SinCrear() throws Exception {
        crear(establecimientoA.getId(), jugador)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve403SinCrear")
    void empleadoConPermiso_Devuelve403SinCrear() throws Exception {
        crear(establecimientoA.getId(), empleadoConPermiso)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403SinCrear")
    void empleadoConTodosLosPermisos_Devuelve403SinCrear() throws Exception {
        // Lo que corta es el rol, no la falta de permisos.
        crear(establecimientoA.getId(), empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403SinCrear")
    void duenoDeOtroEstablecimiento_Devuelve403SinCrear() throws Exception {
        // CanchaService:59 -> AutorizacionEmpleadoService:135
        crear(establecimientoA.getId(), duenoB)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("idDeEstablecimientoInexistente_Devuelve403IgualAlAjeno")
    void idDeEstablecimientoInexistente_Devuelve403() throws Exception {
        crear(999_999L, duenoA)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("idDeEstablecimientoInexistenteComoAdmin_Devuelve404")
    void idDeEstablecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        crear(999_999L, admin)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("tarifasSolapadasDeDuenoAjeno_Devuelve403No400")
    void tarifasSolapadasDeDuenoAjeno_Devuelve403No400() throws Exception {
        String solapadas = "{\"nombre\":\"X\",\"deportes\":[\"PADEL\"],\"precioBase\":1500,\"tarifas\":["
                + "{\"diaSemana\":\"MONDAY\",\"horaInicio\":\"10:00\",\"horaFin\":\"14:00\",\"precio\":100},"
                + "{\"diaSemana\":\"MONDAY\",\"horaInicio\":\"12:00\",\"horaFin\":\"16:00\",\"precio\":100}]}";
        String url = "/api/v1/establecimientos/" + establecimientoA.getId() + "/canchas";
        mockMvc.perform(post(url).header("Authorization", bearer(duenoB)).contentType("application/json").content(solapadas))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        mockMvc.perform(post(url).header("Authorization", bearer(duenoA)).contentType("application/json").content(solapadas))
                .andExpect(status().isBadRequest());
        assertCantidades(1, 0);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201YCreaLaCanchaEnEseComplejo")
    void duenoDelEstablecimiento_Devuelve201YCreaLaCanchaEnEseComplejo() throws Exception {
        crear(establecimientoA.getId(), duenoA)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nombre").value("Cancha Nueva"));
        assertCantidades(2, 0);
    }

    @Test
    @DisplayName("admin_Devuelve201YCreaLaCanchaEnElComplejoAjeno")
    void admin_Devuelve201YCreaLaCanchaEnElComplejoAjeno() throws Exception {
        crear(establecimientoB.getId(), admin)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nombre").value("Cancha Nueva"));
        assertCantidades(1, 1);
    }
}
