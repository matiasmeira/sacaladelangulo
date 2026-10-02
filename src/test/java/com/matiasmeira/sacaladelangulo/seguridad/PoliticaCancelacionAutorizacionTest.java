package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Política de cancelación del complejo (horas mínimas de anticipación y minutos de gracia para que el
 * jugador cancele): GET y PATCH /api/v1/establecimientos/{e}/politicas-cancelacion. @PreAuthorize
 * OWNER/ADMIN en ambos (PoliticaCancelacionController:33 y :46): el empleado queda afuera aunque tenga todos
 * los permisos. PoliticaCancelacionService autoriza con validarPropietarioOAdmin (líneas 38 y 53 ->
 * AutorizacionEmpleadoService:135). Defaults del complejo: 24 horas y 30 minutos.
 *
 * <p>Sin oráculo de existencia (pendiente 69): el service autoriza contra el establecimiento del path antes
 * de validar el body, así que un establecimiento inexistente responde el mismo 403 que uno ajeno y el PATCH
 * sin ningún campo responde 400 sólo a quien está autorizado.
 */
@DisplayName("Política de cancelación /api/v1/establecimientos/{e}/politicas-cancelacion")
class PoliticaCancelacionAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    @Autowired
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    private String ruta() {
        return "/api/v1/establecimientos/" + establecimientoA.getId() + "/politicas-cancelacion";
    }

    private Usuario conTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    private Establecimiento recargar(Establecimiento e) {
        return establecimientoRepository.findById(e.getId()).orElseThrow();
    }

    private void assertSinCambios() {
        assertEquals(24, recargar(establecimientoA).getHorasCancelacionAntesPartido());
        assertEquals(30, recargar(establecimientoA).getMinutosGraciaCancelacion());
        assertEquals(24, recargar(establecimientoB).getHorasCancelacionAntesPartido());
        assertEquals(0, registroAuditoriaRepository.count());
    }

    private static final long ID_INEXISTENTE = 987654321L;

    private static String rutaDe(long establecimientoId) {
        return "/api/v1/establecimientos/" + establecimientoId + "/politicas-cancelacion";
    }

    @Nested
    @DisplayName("sin oráculo de existencia")
    class SinOraculo {
        @Test
        @DisplayName("get_establecimientoInexistente_Devuelve403IgualAlAjeno")
        void getInexistente() throws Exception {
            mockMvc.perform(get(rutaDe(ID_INEXISTENTE)).header("Authorization", bearer(duenoA)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        }

        @Test
        @DisplayName("patch_establecimientoInexistente_Devuelve403IgualAlAjeno")
        void patchInexistente() throws Exception {
            mockMvc.perform(patch(rutaDe(ID_INEXISTENTE)).header("Authorization", bearer(duenoA))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"horasCancelacionAntesPartido\":12}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertSinCambios();
        }

        @Test
        @DisplayName("patch_establecimientoInexistenteComoAdmin_Devuelve404")
        void patchInexistenteAdmin() throws Exception {
            mockMvc.perform(patch(rutaDe(ID_INEXISTENTE)).header("Authorization", bearer(admin))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"horasCancelacionAntesPartido\":12}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
        }

        @Test
        @DisplayName("patch_bodyVacioDeDuenoAjeno_Devuelve403No400")
        void patchBodyVacioDeAjeno() throws Exception {
            mockMvc.perform(patch(ruta()).header("Authorization", bearer(duenoB))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertSinCambios();
        }

        @Test
        @DisplayName("patch_bodyVacioDeDuenoPropio_Devuelve400")
        void patchBodyVacioDePropio() throws Exception {
            mockMvc.perform(patch(ruta()).header("Authorization", bearer(duenoA))
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
            assertSinCambios();
        }
    }

    @Nested
    @DisplayName("GET")
    class Obtener {
        private ResultActions obtener(Usuario quien) throws Exception {
            return mockMvc.perform(get(ruta()).header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200ConLaPolitica")
        void duenoPropio() throws Exception {
            obtener(duenoA).andExpect(status().isOk())
                    .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(24))
                    .andExpect(jsonPath("$.minutosGraciaCancelacion").value(30))
                    .andExpect(jsonPath("$.reservasFuturasAfectadas").doesNotExist());
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            obtener(admin).andExpect(status().isOk()).andExpect(jsonPath("$.horasCancelacionAntesPartido").value(24));
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403")
        void duenoAjeno() throws Exception {
            obtener(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            obtener(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            obtener(conTodosLosPermisos()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }
    }

    @Nested
    @DisplayName("PATCH")
    class Actualizar {
        private ResultActions actualizar(Usuario quien) throws Exception {
            return mockMvc.perform(patch(ruta()).header("Authorization", bearer(quien))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"horasCancelacionAntesPartido\":12,\"minutosGraciaCancelacion\":45}"));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200PersisteYAuditaSinTocarElOtroComplejo")
        void duenoPropio() throws Exception {
            actualizar(duenoA).andExpect(status().isOk())
                    .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(12))
                    .andExpect(jsonPath("$.minutosGraciaCancelacion").value(45));
            assertEquals(12, recargar(establecimientoA).getHorasCancelacionAntesPartido());
            assertEquals(45, recargar(establecimientoA).getMinutosGraciaCancelacion());
            assertEquals(24, recargar(establecimientoB).getHorasCancelacionAntesPartido());
            assertEquals(1, registroAuditoriaRepository.findAll().stream()
                    .filter(r -> r.getAccion() == AccionAuditoria.ACTUALIZAR_POLITICA_CANCELACION).count());
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            actualizar(admin).andExpect(status().isOk());
            assertEquals(12, recargar(establecimientoA).getHorasCancelacionAntesPartido());
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403SinCambios")
        void duenoAjeno() throws Exception {
            actualizar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertSinCambios();
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacionSinCambios")
        void jugador() throws Exception {
            actualizar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertSinCambios();
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacionSinCambios")
        void empleadoTodos() throws Exception {
            actualizar(conTodosLosPermisos()).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertSinCambios();
        }
    }
}
