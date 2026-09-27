package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fija el contrato observable de DELETE /.../canchas/{canchaId} (deprecado, ver javadoc de
 * CanchaController.desactivarCancha) ahora que delega en CanchaEstadoService en vez de llamar
 * a CanchaService.desactivarCancha directamente. El objetivo de este archivo NO es volver a
 * probar los guards de negocio (eso ya lo cubren CanchaServiceTest,
 * CanchaServiceDesactivacionReversibleTest y CanchaServiceReactivacionEstadoTest) sino asegurar
 * que, del lado de afuera (status, body, mensajes), nadie note que cambió el camino interno --
 * es lo que impide que la Etapa 3 (borrar esta ruta) se lleve puesto algo sin darse cuenta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cancha-desactivar-legacy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/establecimientos/{establecimientoId}/canchas/{canchaId} (deprecado) - contrato sin cambios")
class CanchaControllerDesactivarCanchaDeprecadaContratoTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;
    @Autowired
    private ReservaRepository reservaRepository;

    private Usuario crearUsuario(Role rol) {
        String sufijo = "u" + CONTADOR.incrementAndGet();
        return usuarioRepository.save(Usuario.builder()
                .email(sufijo + "@cancha-delete-legacy-test.com")
                .password("hash")
                .nombre("Usuario " + sufijo)
                .rol(rol)
                .planSuscripcion(rol == Role.OWNER ? PlanSuscripcion.TRIAL : null)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    private Usuario crearEmpleado(Establecimiento establecimiento) {
        String sufijo = "emp" + CONTADOR.incrementAndGet();
        return usuarioRepository.save(Usuario.builder()
                .email(sufijo + "@cancha-delete-legacy-test.com")
                .password("hash")
                .nombre("Empleado " + sufijo)
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(establecimiento)
                .build());
    }

    private Establecimiento crearEstablecimiento(Usuario dueno) {
        String sufijo = "e" + CONTADOR.incrementAndGet();
        return establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + sufijo)
                .direccion("Calle Falsa 123")
                .slug("complejo-" + sufijo)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)));
    }

    private Cancha crearCancha(Establecimiento establecimiento, boolean activa) {
        return canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .establecimiento(establecimiento)
                .isActive(activa)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(new BigDecimal("10000"))
                .montoSena(new BigDecimal("2000"))
                .build());
    }

    private String ruta(Establecimiento establecimiento, Cancha cancha) {
        return "/api/v1/establecimientos/" + establecimiento.getId() + "/canchas/" + cancha.getId();
    }

    @Test
    @DisplayName("dueno_Desactiva_204_SinBody")
    void dueno_Desactiva_204_SinBody() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isFalse();
    }

    /** Confirma que la autorización sigue siendo OWNER+ADMIN, no se endureció a OWNER puro. */
    @Test
    @DisplayName("admin_Desactiva_204_SinBody")
    void admin_Desactiva_204_SinBody() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario admin = crearUsuario(Role.ADMIN);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isFalse();
    }

    @Test
    @DisplayName("empleado_Desactiva_403")
    void empleado_Desactiva_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Usuario empleado = crearEmpleado(establecimiento);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(empleado.getEmail()).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("otroOwner_Desactiva_403_MismoMensajeQueAntesDeLaMigracion")
    void otroOwner_Desactiva_403_MismoMensajeQueAntesDeLaMigracion() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario otroDueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(otroDueno.getEmail()).roles("OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("canchaInexistente_404_MismoMensajeQueAntesDeLaMigracion")
    void canchaInexistente_404_MismoMensajeQueAntesDeLaMigracion() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);

        mockMvc.perform(delete("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas/999999")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
    }

    @Test
    @DisplayName("canchaYaEliminada_404_MismoMensajeQueAntesDeLaMigracion")
    void canchaYaEliminada_404_MismoMensajeQueAntesDeLaMigracion() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, false);
        cancha.setDeletedAt(LocalDateTime.now());
        canchaRepository.save(cancha);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
    }

    @Test
    @DisplayName("canchaDeOtroEstablecimiento_400_MismoMensajeQueAntesDeLaMigracion")
    void canchaDeOtroEstablecimiento_400_MismoMensajeQueAntesDeLaMigracion() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimientoA = crearEstablecimiento(dueno);
        Establecimiento establecimientoB = crearEstablecimiento(dueno);
        Cancha canchaDeB = crearCancha(establecimientoB, true);

        mockMvc.perform(delete("/api/v1/establecimientos/" + establecimientoA.getId() + "/canchas/" + canchaDeB.getId())
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La cancha no pertenece a este establecimiento"));

        assertThat(canchaRepository.findById(canchaDeB.getId()).orElseThrow().getIsActive()).isTrue();
    }

    /**
     * El guard de validarDesactivacion (bloquea si deja una reserva futura del GRUPO de pool
     * sin cupo) sigue corriendo detrás de la ruta vieja: F1 es la única física del pool de
     * "Cancha combinada" (que necesita 1), y ésta tiene una reserva futura confirmada.
     * Desactivar F1 dejaría esa reserva sin cupo.
     */
    @Test
    @DisplayName("bloqueoPorReservaFuturaDelPool_400_MismoMensajeQueAntesDeLaMigracion")
    void bloqueoPorReservaFuturaDelPool_400_MismoMensajeQueAntesDeLaMigracion() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha f1 = crearCancha(establecimiento, true);

        Cancha combinada = canchaRepository.save(Cancha.builder()
                .nombre("Cancha combinada")
                .establecimiento(establecimiento)
                .isActive(true)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(new BigDecimal("10000"))
                .montoSena(new BigDecimal("2000"))
                .canchasFisicas(new LinkedHashSet<>(Set.of(f1)))
                .canchasNecesarias(1)
                .build());

        Usuario jugador = crearUsuario(Role.PLAYER);
        LocalDateTime inicio = LocalDateTime.now().plusDays(2);
        reservaRepository.save(Reserva.builder()
                .jugador(jugador)
                .cancha(combinada)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .fechaHoraInicio(inicio)
                .fechaHoraFin(inicio.plusHours(1))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(new BigDecimal("10000"))
                .senaPagada(new BigDecimal("2000"))
                .build());

        mockMvc.perform(delete(ruta(establecimiento, f1))
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString(
                        "se queda sin cupo disponible")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Cancha combinada")));

        assertThat(canchaRepository.findById(f1.getId()).orElseThrow().getIsActive()).isTrue();
    }
}
