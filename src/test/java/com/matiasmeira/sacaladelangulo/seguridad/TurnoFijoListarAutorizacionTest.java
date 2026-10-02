package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import com.matiasmeira.sacaladelangulo.support.AbstractTurnoFijoSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/turnos-fijos?establecimientoId=. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (TurnoFijoController:58). El chequeo fino es AutorizacionEmpleadoService.validarLectura
 * (TurnoFijoService:358) con PERMISOS_OPERATIVOS_DE_RESERVA (AutorizacionEmpleadoService:37-41):
 * pasa el admin, el dueño real o un empleado del establecimiento con alguno de esos cuatro
 * permisos; el resto recibe AccessDeniedException (AutorizacionEmpleadoService:78) -> 403.
 * OPERAR_CAJA no está en el set. Sin estado, el listado trae sólo las series ACTIVAS.
 */
@DisplayName("GET /api/v1/turnos-fijos")
class TurnoFijoListarAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private TurnoFijo serieA;

    @BeforeEach
    void sembrarSeries() {
        serieA = serieDeA();
        serie(canchaA, EstadoTurnoFijo.CANCELADO);
        serie(canchaB, EstadoTurnoFijo.ACTIVO);
    }

    private ResultActions listar(Long establecimientoId, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/turnos-fijos")
                .param("establecimientoId", String.valueOf(establecimientoId))
                .header("Authorization", bearer(usuario)));
    }

    private void assertSoloLaSerieActivaDeA(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(serieA.getId()));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        listar(establecimientoA.getId(), jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoSinPermiso_Devuelve403")
    void empleadoSinPermiso_Devuelve403() throws Exception {
        // TurnoFijoService:358 (AutorizacionEmpleadoService:78)
        listar(establecimientoA.getId(), empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403")
    void empleadoConOperarCaja_Devuelve403() throws Exception {
        // OPERAR_CAJA no está en PERMISOS_OPERATIVOS_DE_RESERVA (AutorizacionEmpleadoService:37-41)
        listar(establecimientoA.getId(), empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @ParameterizedTest(name = "empleadoConPermisoOperativo_{0}_Devuelve200")
    @EnumSource(value = PermisoEmpleado.class, names = {"CREAR_RESERVA_MANUAL", "FINALIZAR_RESERVA", "CANCELAR_RESERVA", "MARCAR_AUSENTE"})
    @DisplayName("empleadoConPermisoOperativo_Devuelve200")
    void empleadoConPermisoOperativo_Devuelve200(PermisoEmpleado permiso) throws Exception {
        assertSoloLaSerieActivaDeA(listar(establecimientoA.getId(), empleado(establecimientoA, Set.of(permiso))));
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403")
    void empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403() throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(PermisoEmpleado.FINALIZAR_RESERVA));
        // TurnoFijoService:358: el permiso vale sólo en el establecimiento del empleado (AutorizacionEmpleadoService:117-123)
        listar(establecimientoA.getId(), empleadoDeB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // TurnoFijoService:358 (AutorizacionEmpleadoService:78)
        listar(establecimientoA.getId(), duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200SoloConLasSeriesActivasDelComplejo")
    void duenoDelEstablecimiento_Devuelve200SoloConLasSeriesActivasDelComplejo() throws Exception {
        assertSoloLaSerieActivaDeA(listar(establecimientoA.getId(), duenoA));
    }

    @Test
    @DisplayName("admin_Devuelve200SoloConLasSeriesActivasDelComplejo")
    void admin_Devuelve200SoloConLasSeriesActivasDelComplejo() throws Exception {
        assertSoloLaSerieActivaDeA(listar(establecimientoA.getId(), admin));
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
    void establecimientoInexistente_Devuelve403() throws Exception {
        // Sin oráculo de existencia (pendiente 69): mismo 403 y mismo mensaje que ante un complejo ajeno
        listar(999999L, duenoA).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("establecimientoInexistenteComoAdmin_Devuelve404")
    void establecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        listar(999999L, admin).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }
}
