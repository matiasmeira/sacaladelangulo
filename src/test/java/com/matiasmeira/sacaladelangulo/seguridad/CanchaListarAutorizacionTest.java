package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.EnumSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{e}/canchas[?incluirInactivas=true]. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (CanchaController:54). Sin incluirInactivas, CanchaService.obtenerCanchasPorEstablecimiento autoriza con
 * validarLectura y PERMISOS_OPERATIVOS_DE_RESERVA (CanchaService:120 -> AutorizacionEmpleadoService:78):
 * dueño, admin o empleado con alguno de CREAR_RESERVA_MANUAL / FINALIZAR_RESERVA / CANCELAR_RESERVA /
 * MARCAR_AUSENTE. Con incluirInactivas=true exige validarPropietarioOAdmin (CanchaService:115 ->
 * AutorizacionEmpleadoService:135): el empleado queda afuera aunque tenga todos los permisos. El jugador lo
 * corta la anotación.
 *
 * <p>El establecimiento A tiene una cancha activa y una desactivada; el B, una activa: el listado nunca
 * mezcla complejos. El establecimiento inexistente responde el mismo 403 que uno ajeno (pendiente
 * 69), con el mensaje del chequeo que corresponda; el ADMIN recibe 404.
 */
@DisplayName("GET /api/v1/establecimientos/{e}/canchas")
class CanchaListarAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_LECTURA = "No autorizado para ver esta información de este establecimiento";
    private static final String MENSAJE_403_PROPIETARIO = "No autorizado en este establecimiento";

    private Cancha canchaInactivaA;

    @BeforeEach
    void sembrarCanchas() {
        canchaInactivaA = canchaRepository.save(Canchas.canchaDesactivada(establecimientoA));
        canchaRepository.save(Canchas.canchaActiva(establecimientoB));
    }

    private ResultActions listar(Usuario quien, boolean incluirInactivas) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoA.getId() + "/canchas"
                + (incluirInactivas ? "?incluirInactivas=true" : "")).header("Authorization", bearer(quien)));
    }

    private void assertSoloActivasDeA(ResultActions r) throws Exception {
        r.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(canchaA.getId()))
                .andExpect(jsonPath("$[0].establecimientoId").value(establecimientoA.getId()));
    }

    private void assertActivasEInactivasDeA(ResultActions r) throws Exception {
        r.andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(canchaA.getId().intValue(), canchaInactivaA.getId().intValue())));
    }

    // ---- listado normal ----

    @Test
    @DisplayName("duenoPropio_Devuelve200SoloActivasDeSuComplejo")
    void duenoPropio() throws Exception {
        assertSoloActivasDeA(listar(duenoA, false));
    }

    @Test
    @DisplayName("admin_Devuelve200")
    void admin() throws Exception {
        assertSoloActivasDeA(listar(admin, false));
    }

    @Test
    @DisplayName("duenoAjeno_Devuelve403")
    void duenoAjeno() throws Exception {
        listar(duenoB, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador() throws Exception {
        listar(jugador, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoSinPermisos_Devuelve403")
    void empleadoSinPermisos() throws Exception {
        listar(empleadoSinPermiso, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("empleadoConSoloOperarCaja_Devuelve403")
    void empleadoSoloCaja() throws Exception {
        listar(empleadoConPermiso, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @ParameterizedTest(name = "empleadoConPermisoOperativo_{0}_Devuelve200")
    @EnumSource(value = PermisoEmpleado.class, names = {"CREAR_RESERVA_MANUAL", "FINALIZAR_RESERVA", "CANCELAR_RESERVA", "MARCAR_AUSENTE"})
    void empleadoConPermisoOperativo(PermisoEmpleado permiso) throws Exception {
        assertSoloActivasDeA(listar(empleado(establecimientoA, Set.of(permiso)), false));
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConTodosLosPermisos_Devuelve403")
    void empleadoDeOtroEstablecimiento() throws Exception {
        Usuario deB = empleado(establecimientoB, EnumSet.allOf(PermisoEmpleado.class));
        listar(deB, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    // ---- incluirInactivas=true ----

    @Test
    @DisplayName("incluirInactivas_duenoPropio_Devuelve200ConLasDesactivadas")
    void inactivasDuenoPropio() throws Exception {
        assertActivasEInactivasDeA(listar(duenoA, true));
    }

    @Test
    @DisplayName("incluirInactivas_admin_Devuelve200")
    void inactivasAdmin() throws Exception {
        assertActivasEInactivasDeA(listar(admin, true));
    }

    @Test
    @DisplayName("incluirInactivas_duenoAjeno_Devuelve403")
    void inactivasDuenoAjeno() throws Exception {
        listar(duenoB, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
    }

    @Test
    @DisplayName("incluirInactivas_jugador_Devuelve403PorAnotacion")
    void inactivasJugador() throws Exception {
        listar(jugador, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("incluirInactivas_empleadoConTodosLosPermisos_Devuelve403")
    void inactivasEmpleadoTodos() throws Exception {
        Usuario todo = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        listar(todo, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
    }

    private ResultActions listarEn(long establecimientoId, Usuario quien, boolean incluirInactivas) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoId + "/canchas"
                + (incluirInactivas ? "?incluirInactivas=true" : "")).header("Authorization", bearer(quien)));
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
    void establecimientoInexistente_Devuelve403IgualAlAjeno() throws Exception {
        listarEn(999_999L, duenoB, false).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
        listarEn(999_999L, duenoA, false).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
        listarEn(999_999L, duenoA, true).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
    }

    @Test
    @DisplayName("establecimientoInexistenteComoAdmin_Devuelve404")
    void establecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        listarEn(999_999L, admin, false).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }
}
