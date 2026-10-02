package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.feedback.model.Feedback;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.support.AbstractFeedbackSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/establecimientos/{id}/feedback. @PreAuthorize OWNER/ADMIN/EMPLOYEE (FeedbackController:78). El service
 * busca el establecimiento (FeedbackService:150, 404 "Establecimiento no encontrado") y exige
 * validarAccion(FIJAR_COMENTARIO_DESTACADO) (:152; AutorizacionEmpleadoService:59, 403 "No autorizado para realizar
 * esta acción en este establecimiento"). La respuesta lleva jugadorId y jugadorNombre (FeedbackMapper) pero nunca el
 * email: eso sólo lo ve quien pasó la autorización.
 */
@DisplayName("GET /api/v1/establecimientos/{id}/feedback")
class FeedbackListarAutorizacionTest extends AbstractFeedbackSecurityTest {

    private Feedback feedbackA;
    private Feedback feedbackB;
    private Usuario empleadoConFijar;

    @BeforeEach
    void sembrarFeedbacks() {
        feedbackA = feedback(reserva(canchaA, jugador, EstadoReserva.FINALIZADA), false);
        feedbackB = feedback(reserva(canchaB, jugadorExtra(), EstadoReserva.FINALIZADA), false);
        empleadoConFijar = empleado(establecimientoA, Set.of(PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO));
    }

    private ResultActions listar(Long establecimientoId, Usuario usuario) throws Exception {
        return mockMvc.perform(get("/api/v1/establecimientos/" + establecimientoId + "/feedback")
                .header("Authorization", bearer(usuario)));
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador_Devuelve403PorAnotacion() throws Exception {
        listar(establecimientoA.getId(), jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @ParameterizedTest(name = "{0}_Devuelve403DelService")
    @ValueSource(strings = {"empleadoSinPermiso", "empleadoConOperarCaja", "empleadoDeB", "duenoB"})
    @DisplayName("sinAcceso_Devuelve403DelService")
    void sinAcceso_Devuelve403DelService(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "empleadoSinPermiso" -> empleadoSinPermiso;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            case "empleadoDeB" -> empleado(establecimientoB, Set.of(PermisoEmpleado.FIJAR_COMENTARIO_DESTACADO));
            default -> duenoB;
        };

        // FeedbackService:152 -> AutorizacionEmpleadoService:59
        listar(establecimientoA.getId(), usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
    }

    @ParameterizedTest(name = "{0}_Devuelve200SoloConLosDeA")
    @ValueSource(strings = {"empleadoConFijar", "duenoA", "admin"})
    @DisplayName("conAcceso_Devuelve200SoloConLosFeedbacksDeA")
    void conAcceso_Devuelve200SoloConLosFeedbacksDeA(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "empleadoConFijar" -> empleadoConFijar;
            case "duenoA" -> duenoA;
            default -> admin;
        };

        listar(establecimientoA.getId(), usuario).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(feedbackA.getId().intValue())))
                .andExpect(jsonPath("$.content[0].jugadorId").value(jugador.getId()))
                .andExpect(jsonPath("$.content[0].jugadorNombre").value(jugador.getNombre()))
                .andExpect(jsonPath("$.content[0].email").doesNotExist())
                .andExpect(jsonPath("$.content[0].jugadorEmail").doesNotExist());
    }

    @Test
    @DisplayName("duenoDeB_VeSoloLosDeB")
    void duenoDeB_VeSoloLosDeB() throws Exception {
        listar(establecimientoB.getId(), duenoB).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(feedbackB.getId()));
    }

    @Test
    @DisplayName("duenoConEstablecimientoInexistente_Devuelve403IgualAlAjeno")
    void duenoConEstablecimientoInexistente_Devuelve403() throws Exception {
        // Sin oráculo de existencia (pendiente 69): mismo 403 y mismo mensaje que ante un complejo ajeno
        listar(999_999L, duenoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
    }

    @Test
    @DisplayName("adminConEstablecimientoInexistente_Devuelve404")
    void adminConEstablecimientoInexistente_Devuelve404() throws Exception {
        listar(999_999L, admin).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }
}
