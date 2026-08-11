package com.matiasmeira.sacaladelangulo.empleado.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET .../empleados serializa Usuario.permisos (colección lazy) fuera de la transacción
 * de EmpleadoService (spring.jpa.open-in-view=false): sin materializarla dentro de
 * EmpleadoMapper, cualquier empleado listado tira LazyInitializationException. Este test
 * end-to-end (MockMvc + JWT real + H2 real, mismo patrón que UsuarioControllerMeTest)
 * ejercita ese camino de punta a punta para que un mock no pueda esconder la regresión.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-empleado-listar;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/establecimientos/{id}/empleados")
class EmpleadoControllerListarTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("admin_ListaEmpleadosDelEstablecimiento_ConSusPermisos")
    void admin_ListaEmpleadosDelEstablecimiento_ConSusPermisos() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin@empleado-listar-test.com")
                .password("hash")
                .nombre("Admin Test")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno@empleado-listar-test.com")
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
                .slug("cancha-test-empleados")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(dueno)
                .build());

        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@empleado-listar-test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA, PermisoEmpleado.CANCELAR_RESERVA))
                .build());

        String token = jwtService.generateToken(UsuarioUserDetailsMapper.map(admin));

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/empleados")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(is(empleado.getId()), Long.class))
                .andExpect(jsonPath("$[0].nombre").value("Empleado Test"))
                .andExpect(jsonPath("$[0].activo").value(true))
                .andExpect(jsonPath("$[0].establecimientoId").value(is(establecimiento.getId()), Long.class))
                .andExpect(jsonPath("$[0].permisos", containsInAnyOrder("OPERAR_CAJA", "CANCELAR_RESERVA")));
    }
}
