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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/v1/reservas/{id}/revertir-ausencia. @PreAuthorize OWNER/ADMIN (ReservaController:143): un
 * empleado no puede revertir ni siquiera con MARCAR_AUSENTE. El chequeo fino es validarPropietarioOAdmin
 * sobre el establecimiento de la reserva (ReservaService:803, mensaje en AutorizacionEmpleadoService:135).
 */
@DisplayName("PATCH /api/v1/reservas/{id}/revertir-ausencia")
class ReservaRevertirAusenciaAutorizacionTest extends AbstractReservaSecurityTest {

    private Reserva ausente;

    @BeforeEach
    void sembrarReservaAusente() {
        ausente = reserva(canchaA, jugador, EstadoReserva.AUSENTE, LocalDateTime.now().minusHours(3), 60);
    }

    private ResultActions revertir(Long reservaId, Usuario usuario) throws Exception {
        return mockMvc.perform(patch("/api/v1/reservas/" + reservaId + "/revertir-ausencia")
                .header("Authorization", bearer(usuario)));
    }

    private void assertEstado(EstadoReserva esperado) {
        assertEquals(esperado, reservaRepository.findById(ausente.getId()).orElseThrow().getEstado());
    }

    @ParameterizedTest(name = "{0}_Devuelve403PorAnotacion")
    @ValueSource(strings = {"jugador", "empleadoConOperarCaja", "empleadoConTodosLosPermisos"})
    @DisplayName("rolSinAcceso_Devuelve403PorAnotacion")
    void rolSinAcceso_Devuelve403PorAnotacion(String quien) throws Exception {
        Usuario usuario = switch (quien) {
            case "jugador" -> jugador;
            case "empleadoConOperarCaja" -> empleadoConPermiso;
            // incluye MARCAR_AUSENTE: quien marca ausente no puede revertir
            default -> empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
        };

        revertir(ausente.getId(), usuario).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEstado(EstadoReserva.AUSENTE);
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        revertir(ausente.getId(), duenoB).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_PROPIETARIO));
        assertEstado(EstadoReserva.AUSENTE);
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve200YVuelveAConfirmada")
    void duenoDelEstablecimiento_Devuelve200YVuelveAConfirmada() throws Exception {
        revertir(ausente.getId(), duenoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("admin_Devuelve200YVuelveAConfirmada")
    void admin_Devuelve200YVuelveAConfirmada() throws Exception {
        revertir(ausente.getId(), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CONFIRMADA"));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @Test
    @DisplayName("reservaInexistente_Devuelve404")
    void reservaInexistente_Devuelve404() throws Exception {
        revertir(999999L, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Reserva no encontrada"));
        assertEstado(EstadoReserva.AUSENTE);
    }
}
