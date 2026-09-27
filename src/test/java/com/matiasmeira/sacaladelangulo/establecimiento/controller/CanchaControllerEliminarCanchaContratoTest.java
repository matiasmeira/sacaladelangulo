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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fija el contrato observable de DELETE /.../canchas/{canchaId}: elimina de verdad (deletedAt),
 * no desactiva. El objetivo NO es re-probar los guards de negocio de CanchaEliminacionService
 * (eso ya lo cubre CanchaEliminacionServiceTest) sino asegurar que, del lado de afuera, el
 * verbo+ruta hacen lo que dicen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-cancha-eliminar-contrato;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/establecimientos/{establecimientoId}/canchas/{canchaId} (eliminación real)")
class CanchaControllerEliminarCanchaContratoTest {

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
                .email(sufijo + "@cancha-delete-real-test.com")
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
                .email(sufijo + "@cancha-delete-real-test.com")
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
    @DisplayName("dueno_Elimina_204_SeteaDeletedAt_NoSoloDesactiva")
    void dueno_Elimina_204_SeteaDeletedAt_NoSoloDesactiva() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, false);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Cancha eliminada = canchaRepository.findById(cancha.getId()).orElseThrow();
        assertThat(eliminada.getDeletedAt()).isNotNull();
    }

    /** A diferencia de la ruta vieja (OWNER+ADMIN), acá sigue rigiendo hasRole('OWNER') puro. */
    @Test
    @DisplayName("admin_403_SoloElDuenoRealPuedeEliminar")
    void admin_403_SoloElDuenoRealPuedeEliminar() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Usuario admin = crearUsuario(Role.ADMIN);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Cancha cancha = crearCancha(establecimiento, false);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(admin.getEmail()).roles("ADMIN")))
                .andExpect(status().isForbidden());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("empleado_403")
    void empleado_403() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);
        Usuario empleado = crearEmpleado(establecimiento);
        Cancha cancha = crearCancha(establecimiento, false);

        mockMvc.perform(delete(ruta(establecimiento, cancha))
                        .with(user(empleado.getEmail()).roles("EMPLOYEE")))
                .andExpect(status().isForbidden());

        assertThat(canchaRepository.findById(cancha.getId()).orElseThrow().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("canchaInexistente_404")
    void canchaInexistente_404() throws Exception {
        Usuario dueno = crearUsuario(Role.OWNER);
        Establecimiento establecimiento = crearEstablecimiento(dueno);

        mockMvc.perform(delete("/api/v1/establecimientos/" + establecimiento.getId() + "/canchas/999999")
                        .with(user(dueno.getEmail()).roles("OWNER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Cancha no encontrada"));
    }
}
