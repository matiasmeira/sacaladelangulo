package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
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
 * GET /api/v1/turnos-fijos/{id}. @PreAuthorize OWNER/ADMIN/EMPLOYEE (TurnoFijoController:72).
 * Mismo chequeo fino que el listado: validarLectura con PERMISOS_OPERATIVOS_DE_RESERVA
 * (TurnoFijoService:382; AutorizacionEmpleadoService:37-41 y :78). El id va solo en el path: no
 * hay establecimiento que cruzar, el "dueño ajeno" es el token de B con el id de una serie de A.
 */
@DisplayName("GET /api/v1/turnos-fijos/{id}")
class TurnoFijoDetalleAutorizacionTest extends AbstractTurnoFijoSecurityTest {

    private TurnoFijo serieA;

    @BeforeEach
    void sembrarSerie() {
        serieA = serieDeA();
    }

    private ResultActions detalle(Long id, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/turnos-fijos/" + id).header("Authorization", bearer(usuario)));
    }

    private void assertDetalleDeLaSerie(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(serieA.getId()))
                .andExpect(jsonPath("$.ocurrencias.length()").value(1));
    }

    @Test
    @DisplayName("jugador_Devuelve403")
    void jugador_Devuelve403() throws Exception {
        detalle(serieA.getId(), jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoSinPermiso_Devuelve403")
    void empleadoSinPermiso_Devuelve403() throws Exception {
        // TurnoFijoService:382 (AutorizacionEmpleadoService:78)
        detalle(serieA.getId(), empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403")
    void empleadoConOperarCaja_Devuelve403() throws Exception {
        // OPERAR_CAJA no está en PERMISOS_OPERATIVOS_DE_RESERVA (AutorizacionEmpleadoService:37-41)
        detalle(serieA.getId(), empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @ParameterizedTest(name = "empleadoConPermisoOperativo_{0}_Devuelve200")
    @EnumSource(value = PermisoEmpleado.class, names = {"CREAR_RESERVA_MANUAL", "FINALIZAR_RESERVA", "CANCELAR_RESERVA", "MARCAR_AUSENTE"})
    @DisplayName("empleadoConPermisoOperativo_Devuelve200")
    void empleadoConPermisoOperativo_Devuelve200(PermisoEmpleado permiso) throws Exception {
        assertDetalleDeLaSerie(detalle(serieA.getId(), empleado(establecimientoA, Set.of(permiso))));
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403")
    void empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403() throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(PermisoEmpleado.FINALIZAR_RESERVA));
        // TurnoFijoService:382: el permiso vale sólo en el establecimiento del empleado (AutorizacionEmpleadoService:117-123)
        detalle(serieA.getId(), empleadoDeB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // TurnoFijoService:382 (AutorizacionEmpleadoService:78)
        detalle(serieA.getId(), duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200ConLasOcurrencias")
    void duenoDelEstablecimiento_Devuelve200ConLasOcurrencias() throws Exception {
        assertDetalleDeLaSerie(detalle(serieA.getId(), duenoA));
    }

    @Test
    @DisplayName("admin_Devuelve200ConLasOcurrencias")
    void admin_Devuelve200ConLasOcurrencias() throws Exception {
        assertDetalleDeLaSerie(detalle(serieA.getId(), admin));
    }

    @Test
    @DisplayName("idInexistente_Devuelve404")
    void idInexistente_Devuelve404() throws Exception {
        // TurnoFijoService:391-392
        detalle(999999L, duenoA).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("Turno fijo no encontrado"));
    }
}
