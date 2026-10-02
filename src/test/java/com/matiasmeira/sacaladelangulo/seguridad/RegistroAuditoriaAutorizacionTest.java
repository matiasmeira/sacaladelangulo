package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.model.RegistroAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{e}/registro-auditoria. @PreAuthorize OWNER/ADMIN
 * (RegistroAuditoriaController:30): el empleado queda afuera aunque tenga todos los permisos.
 * RegistroAuditoriaService.listarPorEstablecimiento autoriza con validarPropietarioOAdmin (línea 137 ->
 * AutorizacionEmpleadoService:135). Se siembran dos registros en A y uno en B para aseverar que cada
 * dueño sólo ve los de su complejo.
 *
 * <p>El establecimiento inexistente da 404 antes de autorizar (RegistroAuditoriaService:135-136, pendiente
 * 69, abierto): no se testea.
 */
@DisplayName("GET /api/v1/establecimientos/{e}/registro-auditoria")
class RegistroAuditoriaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado en este establecimiento";

    @Autowired
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    @BeforeEach
    void sembrarRegistros() {
        registro(establecimientoA, "Apertura A1");
        registro(establecimientoA, "Apertura A2");
        registro(establecimientoB, "Apertura B1");
    }

    private void registro(Establecimiento e, String detalle) {
        registroAuditoriaRepository.save(RegistroAuditoria.builder()
                .establecimiento(e).actorId(duenoA.getId()).accion(AccionAuditoria.ABRIR_CAJA)
                .exitoso(true).detalle(detalle).fechaHora(LocalDateTime.now()).build());
    }

    private ResultActions listar(Usuario quien) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/registro-auditoria")
                .header("Authorization", bearer(quien)));
    }

    @Test
    @DisplayName("duenoPropio_Devuelve200SoloConSusRegistros")
    void duenoPropio() throws Exception {
        listar(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[?(@.detalle == 'Apertura B1')]").isEmpty());
    }

    @Test
    @DisplayName("admin_Devuelve200")
    void admin() throws Exception {
        listar(admin).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    @DisplayName("duenoAjeno_Devuelve403")
    void duenoAjeno() throws Exception {
        listar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador() throws Exception {
        listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403PorAnotacion")
    void empleadoConPermiso() throws Exception {
        listar(empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
    void empleadoTodos() throws Exception {
        Usuario todo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        listar(todo).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
