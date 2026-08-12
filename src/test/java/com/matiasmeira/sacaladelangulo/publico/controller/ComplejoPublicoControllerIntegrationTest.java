package com.matiasmeira.sacaladelangulo.publico.controller;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.HorarioAtencion;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.CanchaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "jwt.secret=test-secret-de-al-menos-32-bytes-1234567890"
})
@Transactional
@DisplayName("ComplejoPublicoController - Zona pública sin autenticación (end-to-end)")
class ComplejoPublicoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private CanchaRepository canchaRepository;

    private Establecimiento seedComplejoActivo() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-e2e@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = Establecimiento.builder()
                .nombre("Complejo E2E")
                .direccion("Calle E2E 123")
                .slug("complejo-e2e")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build();
        establecimiento.setHorariosAtencion(new ArrayList<>(List.of(HorarioAtencion.builder()
                .diaSemana(DayOfWeek.MONDAY)
                .horaApertura(LocalTime.of(9, 0))
                .horaCierre(LocalTime.of(23, 0))
                .establecimiento(establecimiento)
                .build())));
        establecimiento = establecimientoRepository.save(establecimiento);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .duracionesPermitidas(List.of(60))
                .establecimiento(establecimiento)
                .build();
        cancha.setTarifas(List.of(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(6000))
                .build()));
        canchaRepository.save(cancha);

        return establecimiento;
    }

    @Test
    @DisplayName("GET /publico/complejos responde 200 sin Authorization")
    void buscarComplejos_SinAuth_Devuelve200() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} responde 200 sin Authorization y sin duenoId")
    void obtenerDetalle_SinAuth_Devuelve200SinDuenoId() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("complejo-e2e"))
                .andExpect(jsonPath("$.precioDesde").value(5000))
                .andExpect(jsonPath("$.senaDesde").value(1000))
                .andExpect(content().string(not(containsString("duenoId"))));
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con slug inexistente responde 404")
    void obtenerDetalle_SlugInexistente_Devuelve404() throws Exception {
        mockMvc.perform(get("/api/v1/publico/complejos/no-existe"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug} con complejo inactivo responde 404")
    void obtenerDetalle_ComplejoInactivo_Devuelve404() throws Exception {
        Establecimiento activo = seedComplejoActivo();
        activo.setIsActive(false);
        establecimientoRepository.save(activo);

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /publico/complejos/{slug}/disponibilidad responde 200 sin Authorization y sin datos de jugador")
    void obtenerDisponibilidad_SinAuth_Devuelve200SinPii() throws Exception {
        seedComplejoActivo();

        mockMvc.perform(get("/api/v1/publico/complejos/complejo-e2e/disponibilidad")
                        .param("fecha", LocalDate.of(2026, 8, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("jugador"))))
                .andExpect(content().string(not(containsString("titular"))));
    }
}
