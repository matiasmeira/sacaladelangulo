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
 * GET .../establecimientos/{id}/disponibilidad sobre un establecimiento deshabilitado
 * (isActive=false): cierra la brecha por la que un PLAYER podía pedir la grilla yendo
 * directo por id, esquivando el filtro del buscador público (ver EstablecimientoOperativoGuard
 * y DisponibilidadService.obtenerDisponibilidad). El camino del panel (dueño viendo su
 * propia agenda) sigue funcionando a propósito: el dueño necesita poder seguir operando
 * sobre lo ya cargado aunque el establecimiento esté deshabilitado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-disponibilidad-inactivo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET /api/v1/establecimientos/{id}/disponibilidad - establecimiento deshabilitado")
class DisponibilidadControllerEstablecimientoInactivoIntegrationTest {

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

    private Establecimiento crearEstablecimientoConCancha(String slug, Usuario dueno, boolean establecimientoActivo) {
        // Este test suite aísla la dimensión isActive: siempre VERIFICADO para que
        // el gate que se ejercite acá sea el de "deshabilitado", no el de verificación.
        Establecimiento establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(establecimientoActivo)
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
    @WithMockUser(username = "jugador-inactivo-a@test.com", roles = "PLAYER")
    @DisplayName("PLAYER pide disponibilidad por id de un establecimiento inactivo -> 404 opaco")
    void jugador_EstablecimientoInactivo_Devuelve404() throws Exception {
        Usuario dueno = crearUsuario("dueno-inactivo-a@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-inactivo-a", dueno, false);
        crearUsuario("jugador-inactivo-a@test.com", Role.PLAYER);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @WithMockUser(username = "dueno-inactivo-b@test.com", roles = "OWNER")
    @DisplayName("OWNER pide disponibilidad de SU establecimiento inactivo -> 200 (no se rompe el panel)")
    void dueno_SuEstablecimientoInactivo_Devuelve200() throws Exception {
        Usuario dueno = crearUsuario("dueno-inactivo-b@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-inactivo-b", dueno, false);

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/disponibilidad")
                        .param("fecha", FECHA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dias[0].canchas[0].ocupadaPorPool").isArray());
    }

    /**
     * PERMISOS_OPERATIVOS_DE_RESERVA (el set que puebla ocupadaPorPool) es más angosto que
     * "es staff de este establecimiento": OPERAR_CAJA no está incluido ahí. Este empleado es
     * staff legítimo (pertenece al establecimiento) aunque no vea ocupadaPorPool, y no debe
     * recibir el 404 opaco reservado para quien no tiene ningún vínculo con el
     * establecimiento -- ver el comentario de obtenerDisponibilidadParaPanel.
     */
    @Test
    @WithMockUser(username = "empleado-caja-inactivo@test.com", roles = "EMPLOYEE")
    @DisplayName("EMPLOYEE con permiso no-operativo (OPERAR_CAJA) accede a la agenda de SU establecimiento inactivo -> 200")
    void empleadoSoloConPermisoDeCaja_SuEstablecimientoInactivo_Devuelve200() throws Exception {
        Usuario dueno = crearUsuario("dueno-inactivo-c@test.com", Role.OWNER);
        Establecimiento establecimiento = crearEstablecimientoConCancha("complejo-inactivo-c", dueno, false);

        usuarioRepository.save(Usuario.builder()
                .email("empleado-caja-inactivo@test.com")
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
