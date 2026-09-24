package com.matiasmeira.sacaladelangulo.disponibilidad.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.EstadoVerificacion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Análoga a DisponibilidadControllerEstablecimientoInactivoIntegrationTest pero para la otra
 * mitad del criterio de EstablecimientoOperativoGuard: estadoVerificacion. El camino del
 * panel (dueño y empleado viendo la agenda de SU PROPIO establecimiento) sigue funcionando a
 * propósito aunque el establecimiento todavía no esté verificado: obtenerDisponibilidadParaPanel
 * sólo consulta al guard cuando quien pregunta NO pertenece al establecimiento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-disponibilidad-no-verificado;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/establecimientos/{id}/disponibilidad - establecimiento no verificado")
class DisponibilidadControllerEstablecimientoNoVerificadoIntegrationTest {

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

    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno, EstadoVerificacion estadoVerificacion) {
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .estadoVerificacion(estadoVerificacion)
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

    @ParameterizedTest(name = "PLAYER pide disponibilidad por id de un establecimiento {0} -> 404 opaco")
    @EnumSource(value = EstadoVerificacion.class, names = "VERIFICADO", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("jugador_EstablecimientoNoVerificado_Devuelve404")
    void jugador_EstablecimientoNoVerificado_Devuelve404(EstadoVerificacion estadoVerificacion) throws Exception {
        String emailJugador = "jugador-nv-" + estadoVerificacion + "@test.com";
        Usuario dueno = crearUsuario("dueno-nv-a-" + estadoVerificacion + "@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha(
                "complejo-nv-a-" + estadoVerificacion.name().toLowerCase(), dueno, estadoVerificacion);
        crearUsuario(emailJugador, Role.PLAYER);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .with(user(emailJugador).roles("PLAYER"))
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @WithMockUser(username = "dueno-nv-b@test.com", roles = "OWNER")
    @DisplayName("OWNER pide disponibilidad de SU establecimiento PENDIENTE -> 200 (no se rompe el panel)")
    void dueno_SuEstablecimientoPendiente_Devuelve200() throws Exception {
        Usuario dueno = crearUsuario("dueno-nv-b@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-nv-b", dueno, EstadoVerificacion.PENDIENTE);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").isArray());
    }

    /**
     * Mismo razonamiento que la contraparte "inactivo": PERMISOS_OPERATIVOS_DE_RESERVA (el
     * set que puebla ocupadaPorPool) es más angosto que "es staff de este establecimiento".
     * OPERAR_CAJA no está ahí, pero igual es staff legítimo y no debe recibir el 404 opaco.
     */
    @Test
    @WithMockUser(username = "empleado-caja-nv@test.com", roles = "EMPLOYEE")
    @DisplayName("EMPLOYEE con permiso no-operativo (OPERAR_CAJA) accede a la agenda de SU establecimiento PENDIENTE -> 200")
    void empleadoSoloConPermisoDeCaja_SuEstablecimientoPendiente_Devuelve200() throws Exception {
        Usuario dueno = crearUsuario("dueno-nv-c@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-nv-c", dueno, EstadoVerificacion.PENDIENTE);

        usuarioRepository.save(Usuario.builder()
                .email("empleado-caja-nv@test.com")
                .password("hash")
                .nombre("Empleado Caja")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(PermisoEmpleado.OPERAR_CAJA))
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").value(nullValue()));
    }
}
