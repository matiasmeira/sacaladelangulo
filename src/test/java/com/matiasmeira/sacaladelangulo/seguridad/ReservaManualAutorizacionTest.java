package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.model.RegistroAuditoria;
import com.matiasmeira.sacaladelangulo.empleado.repository.RegistroAuditoriaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.support.AbstractReservaSecurityTest;
import com.matiasmeira.sacaladelangulo.support.Canchas;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/reservas/manual. @PreAuthorize OWNER/ADMIN/EMPLOYEE (ReservaController:62). El chequeo
 * fino es AutorizacionEmpleadoService.validarAccion sobre el establecimiento de la CANCHA con
 * CREAR_RESERVA_MANUAL (ReservaService:223-224, mensaje en AutorizacionEmpleadoService:59). Corre antes
 * que las validaciones de cancha desactivada (:225) y establecimiento operativo (:226). Si crea un
 * empleado queda una fila de auditoría (:279-280); si crea un dueño o admin, no.
 * Las reglas de negocio del alta (solapamiento, deporte, fechas) tienen sus tests de service.
 */
@DisplayName("POST /api/v1/reservas/manual")
class ReservaManualAutorizacionTest extends AbstractReservaSecurityTest {

    @Autowired
    private RegistroAuditoriaRepository registroAuditoriaRepository;

    private void assertNadaCambio() {
        assertEquals(0, reservaRepository.count());
        assertEquals(0, registroAuditoriaRepository.count());
    }

    private ResultActions assert403Accion(ResultActions resultado) throws Exception {
        return resultado.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
    }

    private Reserva assertReservaManualCreadaEn(Cancha cancha) {
        List<Reserva> reservas = reservaRepository.findAll();
        assertEquals(1, reservas.size());
        Reserva creada = reservas.get(0);
        assertEquals(cancha.getId(), creada.getCancha().getId());
        assertEquals(EstadoReserva.CONFIRMADA, creada.getEstado());
        assertNull(creada.getJugador());
        assertEquals("Cliente Mostrador", creada.getNombreClienteManual());
        assertNull(creada.getExpiraEn());
        assertEquals(0, new BigDecimal("1000").compareTo(creada.getPrecioTotal()));
        return creada;
    }

    @Test
    @DisplayName("jugador_Devuelve403PorAnotacion")
    void jugador_Devuelve403PorAnotacion() throws Exception {
        postManual(canchaA, jugador).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        assertNadaCambio();
    }

    @Test
    @DisplayName("empleadoSinPermiso_Devuelve403")
    void empleadoSinPermiso_Devuelve403() throws Exception {
        assert403Accion(postManual(canchaA, empleadoSinPermiso));
        assertNadaCambio();
    }

    @Test
    @DisplayName("empleadoConOperarCaja_Devuelve403")
    void empleadoConOperarCaja_Devuelve403() throws Exception {
        // OPERAR_CAJA no habilita crear reservas manuales: el permiso es CREAR_RESERVA_MANUAL
        assert403Accion(postManual(canchaA, empleadoConPermiso));
        assertNadaCambio();
    }

    @Test
    @DisplayName("empleadoDeOtroEstablecimientoConPermiso_Devuelve403")
    void empleadoDeOtroEstablecimientoConPermiso_Devuelve403() throws Exception {
        Usuario empleadoDeB = empleado(establecimientoB, Set.of(PermisoEmpleado.CREAR_RESERVA_MANUAL));
        assert403Accion(postManual(canchaA, empleadoDeB));
        assertNadaCambio();
    }

    @Test
    @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
    void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
        // la cancha manda: validarAccion mira el establecimiento de la cancha, no el del usuario
        assert403Accion(postManual(canchaA, duenoB));
        assertNadaCambio();
    }

    @Test
    @DisplayName("empleadoSinPermisoSobreCanchaDesactivada_Devuelve403PorqueLaAutorizacionVaPrimero")
    void empleadoSinPermisoSobreCanchaDesactivada_Devuelve403PorqueLaAutorizacionVaPrimero() throws Exception {
        Cancha desactivada = canchaRepository.save(Canchas.canchaDesactivada(establecimientoA));
        // ReservaService:223 antes que :225 (cancha desactivada -> 400)
        assert403Accion(postManual(desactivada, empleadoSinPermiso));
        assertNadaCambio();
    }

    @Test
    @DisplayName("canchaInexistente_Devuelve404")
    void canchaInexistente_Devuelve404() throws Exception {
        Cancha fantasma = Cancha.builder().id(999999L).build();
        postManual(fantasma, duenoA).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
        assertNadaCambio();
    }

    @Test
    @DisplayName("empleadoConPermiso_Devuelve201YDejaAuditoria")
    void empleadoConPermiso_Devuelve201YDejaAuditoria() throws Exception {
        Usuario empleadoA = empleado(establecimientoA, Set.of(PermisoEmpleado.CREAR_RESERVA_MANUAL));

        postManual(canchaA, empleadoA).andExpect(status().isCreated());

        Reserva creada = assertReservaManualCreadaEn(canchaA);
        List<RegistroAuditoria> auditoria = registroAuditoriaRepository.findAll();
        assertEquals(1, auditoria.size());
        RegistroAuditoria fila = auditoria.get(0);
        assertEquals(AccionAuditoria.CREAR_RESERVA_MANUAL, fila.getAccion());
        assertTrue(fila.getExitoso());
        assertEquals(creada.getId(), fila.getEntidadAfectadaId());
        assertEquals(establecimientoA.getId(), fila.getEstablecimiento().getId());
    }

    @Test
    @DisplayName("duenoDelEstablecimiento_Devuelve201SinAuditoria")
    void duenoDelEstablecimiento_Devuelve201SinAuditoria() throws Exception {
        postManual(canchaA, duenoA).andExpect(status().isCreated());

        assertReservaManualCreadaEn(canchaA);
        assertEquals(0, registroAuditoriaRepository.count());
    }

    @Test
    @DisplayName("admin_Devuelve201SinAuditoria")
    void admin_Devuelve201SinAuditoria() throws Exception {
        postManual(canchaA, admin).andExpect(status().isCreated());

        assertReservaManualCreadaEn(canchaA);
        assertEquals(0, registroAuditoriaRepository.count());
    }
}
