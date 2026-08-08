package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-me;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/usuarios/me")
class UsuarioControllerMeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("admin_ObtieneSuPerfil_SinEstablecimientoNiPermisos")
    void admin_ObtieneSuPerfil_SinEstablecimientoNiPermisos() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin@me-test.com")
                .password("hash")
                .nombre("Admin Test")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(is(admin.getId()), Long.class))
                .andExpect(jsonPath("$.email").value("admin@me-test.com"))
                .andExpect(jsonPath("$.nombre").value("Admin Test"))
                .andExpect(jsonPath("$.rol").value("ADMIN"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.telefonoVerificado").value(false))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("owner_ObtieneSuPerfil_ConPlanSuscripcion")
    void owner_ObtieneSuPerfil_ConPlanSuscripcion() throws Exception {
        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner@me-test.com")
                .password("hash")
                .nombre("Owner Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("OWNER"))
                .andExpect(jsonPath("$.planSuscripcion").value("PREMIUM"))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("player_ObtieneSuPerfil_SinEstablecimientoNiPermisos")
    void player_ObtieneSuPerfil_SinEstablecimientoNiPermisos() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("player@me-test.com")
                .password("hash")
                .nombre("Player Test")
                .rol(Role.PLAYER)
                .planSuscripcion(PlanSuscripcion.FREE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(jugador)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("PLAYER"))
                .andExpect(jsonPath("$.establecimientoId").value(nullValue()))
                .andExpect(jsonPath("$.permisos").isEmpty());
    }

    @Test
    @DisplayName("empleado_ObtieneSuPerfilConEstablecimientoIdYPermisos")
    void empleado_ObtieneSuPerfilConEstablecimientoIdYPermisos() throws Exception {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno@me-test.com")
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Cancha Test")
                .direccion("Calle Falsa 123")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)
                .build());

        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@me-test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA, PermisoEmpleado.CANCELAR_RESERVA))
                .build());

        mockMvc.perform(get("/api/v1/usuarios/me").header("Authorization", "Bearer " + tokenPara(empleado)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rol").value("EMPLOYEE"))
                .andExpect(jsonPath("$.establecimientoId").value(is(establecimiento.getId()), Long.class))
                .andExpect(jsonPath("$.permisos", containsInAnyOrder("OPERAR_CAJA", "CANCELAR_RESERVA")));
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/me"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
