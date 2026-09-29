package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/v1/reservas/{id}/finalizar y /ausente. @PreAuthorize OWNER/ADMIN/EMPLOYEE
 * (ReservaController:108 y :126); el jugador queda afuera ahí. El resto lo decide
 * AutorizacionEmpleadoService.validarAccion (línea 59), invocado desde
 * ReservaService.finalizarReserva (línea 676) con FINALIZAR_RESERVA y desde
 * ReservaService.marcarAusente (línea 757) con MARCAR_AUSENTE: dueño ajeno, empleado sin el
 * permiso y empleado de otro establecimiento reciben AccessDeniedException -> 403.
 * La reserva es CONFIRMADA con el turno ya empezado, condición de ambas acciones (~:715).
 */
@DisplayName("PATCH /api/v1/reservas/{id}/finalizar y /ausente")
class ReservaCobroAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_SERVICE = "No autorizado para realizar esta acción en este establecimiento";

    enum Accion {
        FINALIZAR("finalizar", PermisoEmpleado.FINALIZAR_RESERVA, EstadoReserva.FINALIZADA),
        AUSENTE("ausente", PermisoEmpleado.MARCAR_AUSENTE, EstadoReserva.AUSENTE);

        final String ruta;
        final PermisoEmpleado permiso;
        final EstadoReserva estadoResultante;

        Accion(String ruta, PermisoEmpleado permiso, EstadoReserva estadoResultante) {
            this.ruta = ruta;
            this.permiso = permiso;
            this.estadoResultante = estadoResultante;
        }
    }

    @Autowired
    private ReservaRepository reservaRepository;

    private Reserva reserva;

    @BeforeEach
    void crearReservaConfirmadaYaEmpezada() {
        LocalDateTime inicio = LocalDateTime.now().minusHours(3).withNano(0);
        reserva = reservaRepository.save(Reserva.builder()
                .cancha(canchaA)
                .jugador(jugador)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusMinutes(90))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(new BigDecimal("1000"))
                .senaPagada(BigDecimal.ZERO)
                .build());
    }

    private ResultActions ejecutar(Accion accion, Usuario usuario) throws Exception {
        return mockMvc.perform(patch("/api/v1/reservas/" + reserva.getId() + "/" + accion.ruta)
                .header("Authorization", bearer(usuario))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"metodoPago\":\"EFECTIVO\"}"));
    }

    private void assertEstado(EstadoReserva esperado) {
        assertEquals(esperado, reservaRepository.findById(reserva.getId()).orElseThrow().getEstado());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador_Devuelve403PorAnotacion(Accion accion) throws Exception {
        ejecutar(accion, jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403(Accion accion) throws Exception {
        // AutorizacionEmpleadoService:59
        ejecutar(accion, duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("empleadoSinPermisos_Devuelve403")
    void empleadoSinPermisos_Devuelve403(Accion accion) throws Exception {
        // AutorizacionEmpleadoService:59
        ejecutar(accion, empleadoSinPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("empleadoConOtroPermisoPeroNoElDeLaAccion_Devuelve403")
    void empleadoConOtroPermisoPeroNoElDeLaAccion_Devuelve403(Accion accion) throws Exception {
        // empleadoConPermiso tiene sólo OPERAR_CAJA: no habilita finalizar ni marcar ausente
        // (AutorizacionEmpleadoService:117-123, tienePermiso)
        ejecutar(accion, empleadoConPermiso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("empleadoDeOtroEstablecimientoConElPermiso_Devuelve403")
    void empleadoDeOtroEstablecimientoConElPermiso_Devuelve403(Accion accion) throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(accion.permiso));
        // AutorizacionEmpleadoService:117-123: el permiso vale sólo en el establecimiento del empleado
        ejecutar(accion, empleadoDeB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        assertEstado(EstadoReserva.CONFIRMADA);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("empleadoConElPermiso_Devuelve200YCambiaElEstado")
    void empleadoConElPermiso_Devuelve200YCambiaElEstado(Accion accion) throws Exception {
        Usuario empleadoDeA = empleado(establecimientoA, Set.of(accion.permiso));
        ejecutar(accion, empleadoDeA).andExpect(status().isOk());
        assertEstado(accion.estadoResultante);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("duenoDelEstablecimiento_Devuelve200YCambiaElEstado")
    void duenoDelEstablecimiento_Devuelve200YCambiaElEstado(Accion accion) throws Exception {
        ejecutar(accion, duenoA).andExpect(status().isOk());
        assertEstado(accion.estadoResultante);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Accion.class)
    @DisplayName("admin_Devuelve200YCambiaElEstado")
    void admin_Devuelve200YCambiaElEstado(Accion accion) throws Exception {
        ejecutar(accion, admin).andExpect(status().isOk());
        assertEstado(accion.estadoResultante);
    }
}
