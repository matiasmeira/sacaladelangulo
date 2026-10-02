package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.dto.PerfilResponse;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioService;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.service.AdminEstablecimientoVerificacionService;
import com.matiasmeira.sacaladelangulo.establecimiento.service.EstablecimientoService;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pendiente 86 contra Postgres real: el UPDATE condicional de UsuarioRepository (PLAYER -> 1 fila,
 * cualquier otro rol -> 0) y el flujo completo jugador -> dueño -> establecimiento -> verificación,
 * donde el trial arranca recién al verificar (requiere plan TRIAL y fechaFinPrueba en null).
 */
@Tag("testcontainers")
@DisplayName("Convertir en dueño (pendiente 86) contra Postgres real")
class UsuarioRepositoryConvertirEnDuenoPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private UsuarioService usuarioService;
    @Autowired
    private EstablecimientoService establecimientoService;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private AdminEstablecimientoVerificacionService verificacionService;

    @MockitoBean
    private EmailService emailService;

    private Usuario guardar(String email, Role rol, PlanSuscripcion plan) {
        return usuarioRepository.saveAndFlush(Usuario.builder()
                .email(email).password("hash").nombre("Test " + email).rol(rol).planSuscripcion(plan)
                .isActive(true).emailVerified(true).telefonoVerificado(false).build());
    }

    @Test
    @DisplayName("convertirEnDuenoSiEsJugador_Player_ActualizaUnaFilaYDejaOwnerTrialSinTocarTokenVersion")
    void convertirEnDuenoSiEsJugador_Player_ActualizaUnaFilaYDejaOwnerTrialSinTocarTokenVersion() {
        Usuario player = guardar("player@pg-test.com", Role.PLAYER, PlanSuscripcion.FREE);

        assertEquals(1, usuarioRepository.convertirEnDuenoSiEsJugador(player.getId()));

        Usuario despues = usuarioRepository.findById(player.getId()).orElseThrow();
        assertEquals(Role.OWNER, despues.getRol());
        assertEquals(PlanSuscripcion.TRIAL, despues.getPlanSuscripcion());
        assertNull(despues.getFechaFinPrueba());
        assertEquals(player.getTokenVersion(), despues.getTokenVersion());
    }

    @Test
    @DisplayName("convertirEnDuenoSiEsJugador_Owner_Actualiza0FilasYNoTocaElPlan")
    void convertirEnDuenoSiEsJugador_Owner_Actualiza0FilasYNoTocaElPlan() {
        Usuario owner = guardar("owner@pg-test.com", Role.OWNER, PlanSuscripcion.PREMIUM);

        assertEquals(0, usuarioRepository.convertirEnDuenoSiEsJugador(owner.getId()));

        assertEquals(PlanSuscripcion.PREMIUM, usuarioRepository.findById(owner.getId()).orElseThrow().getPlanSuscripcion());
    }

    @Test
    @DisplayName("convertirEnDuenoSiEsJugador_AdminYEmpleado_Actualizan0Filas")
    void convertirEnDuenoSiEsJugador_AdminYEmpleado_Actualizan0Filas() {
        Usuario admin = guardar("admin@pg-test.com", Role.ADMIN, null);
        Usuario empleado = guardar("empleado@pg-test.com", Role.EMPLOYEE, null);

        assertEquals(0, usuarioRepository.convertirEnDuenoSiEsJugador(admin.getId()));
        assertEquals(0, usuarioRepository.convertirEnDuenoSiEsJugador(empleado.getId()));
        assertEquals(Role.ADMIN, usuarioRepository.findById(admin.getId()).orElseThrow().getRol());
        assertEquals(Role.EMPLOYEE, usuarioRepository.findById(empleado.getId()).orElseThrow().getRol());
    }

    @Test
    @DisplayName("flujo_JugadorConvierteCreaEstablecimientoYAlVerificarloArrancaElTrial")
    void flujo_JugadorConvierteCreaEstablecimientoYAlVerificarloArrancaElTrial() {
        Usuario player = guardar("flujo@pg-test.com", Role.PLAYER, PlanSuscripcion.FREE);
        Usuario admin = guardar("admin-flujo@pg-test.com", Role.ADMIN, null);

        PerfilResponse perfil = usuarioService.convertirEnDueno(player.getEmail());
        assertEquals(Role.OWNER, perfil.rol());
        assertEquals(PlanSuscripcion.TRIAL, perfil.planSuscripcion());

        // Con plan TRIAL el complejo no fuerza seña (con FREE quedaría requiereSena=true).
        Long establecimientoId = establecimientoService.crearEstablecimiento(new EstablecimientoRequest(
                "Complejo Flujo", "Calle 1", -34.6, -58.4, false, false, null, null), player.getEmail()).id();
        assertFalse(establecimientoRepository.findById(establecimientoId).orElseThrow().getRequiereSena());

        Establecimiento establecimiento = establecimientoRepository.findById(establecimientoId).orElseThrow();
        establecimiento.setEstadoVerificacion(EstadoVerificacion.EN_REVISION);
        establecimientoRepository.saveAndFlush(establecimiento);

        LocalDateTime antes = LocalDateTime.now();
        verificacionService.verificar(establecimientoId, admin.getEmail());

        Usuario dueno = usuarioRepository.findById(player.getId()).orElseThrow();
        assertNotNull(dueno.getFechaFinPrueba());
        assertEquals(PlanSuscripcion.TRIAL, dueno.getPlanSuscripcion());
        assertEquals(true, dueno.getFechaFinPrueba().isAfter(antes.plusDays(27)));
    }
}
