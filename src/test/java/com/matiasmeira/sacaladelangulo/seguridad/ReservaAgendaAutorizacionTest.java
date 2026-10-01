package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractReservaSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/reservas/establecimiento/{estId}?fecha=. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (ReservaController:189). El chequeo fino es validarLectura con PERMISOS_OPERATIVOS_DE_RESERVA
 * (ReservaService:967, AutorizacionEmpleadoService:37-41 y mensaje en :78): pasa el admin, el dueño real o
 * un empleado del establecimiento con alguno de esos cuatro permisos. OPERAR_CAJA no está en el set.
 */
@DisplayName("GET /api/v1/reservas/establecimiento/{estId}")
class ReservaAgendaAutorizacionTest extends AbstractReservaSecurityTest {

    private Reserva enCanchaA;
    private Reserva enCanchaA2;
    private Reserva enCanchaB;

    @BeforeEach
    void sembrarReservas() {
        enCanchaA = reserva(canchaA, jugador, EstadoReserva.CONFIRMADA);
        enCanchaA2 = reserva(canchaA2, null, EstadoReserva.CONFIRMADA);
        enCanchaB = reserva(canchaB, jugadorExtra(), EstadoReserva.CONFIRMADA);
    }

    private ResultActions agenda(Long establecimientoId, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/reservas/establecimiento/" + establecimientoId)
                .param("fecha", maniana().toString())
                .header("Authorization", bearer(usuario)));
    }

    private void assertSoloLasDeA(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        enCanchaA.getId().intValue(), enCanchaA2.getId().intValue())));
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador_Devuelve403PorAnotacion() throws Exception {
        agenda(establecimientoA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("empleadoSinPermiso_Devuelve403")
    void empleadoSinPermiso_Devuelve403() throws Exception {
        agenda(establecimientoA.getId(), empleadoSinPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403")
    void empleadoConOperarCaja_Devuelve403() throws Exception {
        // OPERAR_CAJA no está en PERMISOS_OPERATIVOS_DE_RESERVA
        agenda(establecimientoA.getId(), empleadoConPermiso).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403")
    void empleadoDeOtroEstablecimientoConPermisoOperativo_Devuelve403() throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(PermisoEmpleado.FINALIZAR_RESERVA));
        agenda(establecimientoA.getId(), empleadoDeB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        agenda(establecimientoA.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_LECTURA));
    }

    @ParameterizedTest(name = "empleadoConPermisoOperativo_{0}_Devuelve200")
    @EnumSource(value = PermisoEmpleado.class,
            names = {"CREAR_RESERVA_MANUAL", "FINALIZAR_RESERVA", "CANCELAR_RESERVA", "MARCAR_AUSENTE"})
    @DisplayName("empleadoConPermisoOperativo_Devuelve200")
    void empleadoConPermisoOperativo_Devuelve200(PermisoEmpleado permiso) throws Exception {
        assertSoloLasDeA(agenda(establecimientoA.getId(), empleado(establecimientoA, Set.of(permiso))));
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200SoloConLasReservasDelComplejo")
    void duenoDelEstablecimiento_Devuelve200SoloConLasReservasDelComplejo() throws Exception {
        assertSoloLasDeA(agenda(establecimientoA.getId(), duenoA));
    }

    @Test
    @DisplayName("admin_Devuelve200SoloConLasReservasDelComplejo")
    void admin_Devuelve200SoloConLasReservasDelComplejo() throws Exception {
        assertSoloLasDeA(agenda(establecimientoA.getId(), admin));
    }

    @Test
    @DisplayName("duenoDeB_Devuelve200SoloConLasDeSuComplejo")
    void duenoDeB_Devuelve200SoloConLasDeSuComplejo() throws Exception {
        agenda(establecimientoB.getId(), duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(enCanchaB.getId()));
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve404")
    void establecimientoInexistente_Devuelve404() throws Exception {
        agenda(999999L, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }
}
