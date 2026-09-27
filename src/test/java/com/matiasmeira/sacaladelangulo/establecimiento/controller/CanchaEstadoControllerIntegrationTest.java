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
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /.../canchas/{canchaId}/estado (ruta nueva). El contrato del DELETE viejo, que ahora
 * delega en el mismo CanchaEstadoService, se fija aparte en
 * CanchaControllerDesactivarCanchaDeprecadaContratoTest. No es @Transactional a propósito,
 * mismo criterio que EstablecimientoVerificacionEstadoControllerIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cancha-estado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("/api/v1/establecimientos/{establecimientoId}/canchas/{canchaId}/estado")
class CanchaEstadoControllerIntegrationTest {

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private CanchaRepository canchaRepository;

    private Usuario crearUsuario(Role rol) {
        String sufijo = "u" + CONTADOR.incrementAndGet();
        return usuarioRepository.save(Usuario.builder()
                .email(sufijo + "@cancha-estado-test.com")
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
                .email(sufijo + "@cancha-estado-test.com")
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

    private String rutaEstado(Establecimiento establecimiento, Cancha cancha) {
        return "/api/v1/establecimientos/" + establecimiento.getId() + "/canchas/" + cancha.getId() + "/estado";
    }

    @Test
    @DisplayName("dueno_Desactiva_200_YReactiva_200")
    void dueno_Desactiva_200_YReactiva_200() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cancha.getId()))
                .andExpect(jsonPath("$.isActive").value(false));

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isFalse();

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    @DisplayName("admin_Desactiva_200")
    void admin_Desactiva_200() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario admin = crearUsuario(Role.ADMIN);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(admin.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @DisplayName("otroOwner_CambiarEstado_403")
    void otroOwner_CambiarEstado_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario otroDueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(otroDueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isForbidden());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isTrue();
    }

    /**
     * Ni la ruta vieja ni la nueva tenían hasta ahora un test de EMPLOYEE: el @PreAuthorize de
     * ambas es hasAnyRole('OWNER','ADMIN'), así que un EMPLOYEE tiene que quedar afuera en el
     * filtro de seguridad, antes de llegar al service.
     */
    @Test
    @DisplayName("empleado_CambiarEstado_403")
    void empleado_CambiarEstado_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Usuario empleado = crearEmpleado(establecimiento);
        Cancha cancha = crearCancha(establecimiento, true);

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(empleado.getEmail()).roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": false}"))
                .andExpect(status().isForbidden());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isTrue();
    }

    /**
     * El agujero de agregar "activo: true": no puede servir para resucitar una cancha con
     * eliminación DEFINITIVA (deletedAt seteado, ver CanchaEliminacionService). buscarCanchaNoEliminada
     * la trata como inexistente.
     */
    @Test
    @DisplayName("reactivarCanchaEliminada_404")
    void reactivarCanchaEliminada_404() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, false);
        cancha.setDeletedAt(LocalDateTime.now());
        canchaRepository.save(cancha);

        mockMvc.perform(patch(rutaEstado(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\": true}"))
                .andExpect(status().isNotFound());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getIsActive()).isFalse();
    }
}
