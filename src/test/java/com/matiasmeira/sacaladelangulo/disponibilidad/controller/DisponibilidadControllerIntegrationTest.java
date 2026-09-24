package com.matiasmeira.sacaladelangulo.disponibilidad.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET .../establecimientos/{id}/disponibilidad puebla ocupadaPorPool sólo para quien
 * tiene acceso de PANEL a ESE establecimiento (dueño, admin, o empleado con permiso
 * operativo de agenda) — estar autenticado no alcanza, porque el @PreAuthorize del
 * endpoint acepta también a PLAYER y registrarse no cuesta nada. Para cualquier otro caso
 * el campo va en null, igual que en la disponibilidad pública (ver
 * ComplejoPublicoControllerIntegrationTest.obtenerDisponibilidad_SinAuth_Devuelve200SinPii,
 * que cubre el caso anónimo).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-disponibilidad-panel;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/establecimientos/{id}/disponibilidad - ocupadaPorPool sólo con acceso de panel a ESE establecimiento")
class DisponibilidadControllerIntegrationTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 8, 10); // lunes

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    private Usuario crearUsuario(String email, Role rol) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario Test")
                .rol(rol)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno) {
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .dueno(dueno)));
        establecimiento.setHorariosAtencion(new ArrayList<>(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build())));
        establecimiento = establecimientoRepository.save(establecimiento);

        canchaRepository.save(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build());

        return establecimiento;
    }

    @Test
    @WithMockUser(username = "dueno-panel-a@test.com", roles = "OWNER")
    @DisplayName("dueño del establecimiento -> ocupadaPorPool poblado (lista, no null)")
    void dueno_ocupadaPorPoolPoblado() throws Exception {
        Usuario dueno = crearUsuario("dueno-panel-a@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-panel-a", dueno);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").isArray());
    }

    @Test
    @WithMockUser(username = "empleado-panel@test.com", roles = "EMPLOYEE")
    @DisplayName("empleado con permiso de lectura de agenda sobre ese establecimiento -> ocupadaPorPool poblado")
    void empleadoConPermiso_ocupadaPorPoolPoblado() throws Exception {
        Usuario dueno = crearUsuario("dueno-panel-b@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-panel-b", dueno);

        usuarioRepository.save(Usuario.builder()
                .email("empleado-panel@test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.FINALIZAR_RESERVA))
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").isArray());
    }

    @Test
    @WithMockUser(username = "jugador-panel@test.com", roles = "PLAYER")
    @DisplayName("jugador autenticado sin acceso de panel -> ocupadaPorPool en null")
    void jugadorAutenticado_ocupadaPorPoolNull() throws Exception {
        Usuario dueno = crearUsuario("dueno-panel-c@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-panel-c", dueno);
        crearUsuario("jugador-panel@test.com", Role.PLAYER);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").value(nullValue()));
    }

    @Test
    @WithMockUser(username = "dueno-panel-d@test.com", roles = "OWNER")
    @DisplayName("dueño de OTRO establecimiento -> ocupadaPorPool en null")
    void duenoDeOtroEstablecimiento_ocupadaPorPoolNull() throws Exception {
        crearUsuario("dueno-panel-d@test.com", Role.OWNER);
        Usuario duenoReal = crearUsuario("dueno-panel-e@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-panel-e", duenoReal);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").value(nullValue()));
    }
}
