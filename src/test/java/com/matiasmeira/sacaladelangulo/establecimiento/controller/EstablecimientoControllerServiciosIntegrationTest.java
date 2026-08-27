package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.establecimiento.dto.EstablecimientoRequest;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Servicio;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /api/v1/establecimientos/{id} sobre el campo servicios. Contrato: null = no
 * modificar, [] = borrar todos (ver EstablecimientoService). Mismo patrón end-to-end
 * (MockMvc + JWT real + H2 real) que EmpleadoControllerListarTest/UsuarioControllerMeTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-establecimiento-servicios;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("PUT /api/v1/establecimientos/{id} - servicios")
class EstablecimientoControllerServiciosIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("dueno_ActualizaConServicios_LosPersisteYElPublicoLosRefleja")
    void dueno_ActualizaConServicios_LosPersisteYElPublicoLosRefleja() throws Exception {
        Usuario dueno = crearDueno("dueno-servicios-1@test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "servicios-test-1", Set.of());

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Complejo Editado",
                "Calle Falsa 123",
                -34.6,
                -58.4,
                false,
                false,
                null,
                Set.of(Servicio.PARRILLA, Servicio.WIFI)
        );

        mockMvc.perform(put("/api/v1/establecimientos/" + establecimiento.getId())
                        .header("Authorization", "Bearer " + tokenPara(dueno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicios", containsInAnyOrder("PARRILLA", "WIFI")));

        mockMvc.perform(get("/api/v1/publico/complejos/servicios-test-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicios", containsInAnyOrder("PARRILLA", "WIFI")));
    }

    @Test
    @DisplayName("dueno_ActualizaSinClaveServicios_DejaLosServiciosExistentesIntactos")
    void dueno_ActualizaSinClaveServicios_DejaLosServiciosExistentesIntactos() throws Exception {
        Usuario dueno = crearDueno("dueno-servicios-2@test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "servicios-test-2",
                Set.of(Servicio.PARRILLA, Servicio.DUCHAS));

        String jsonSinClaveServicios = """
                {
                  "nombre": "Complejo Editado",
                  "direccion": "Calle Falsa 123",
                  "latitud": -34.6,
                  "longitud": -58.4,
                  "requiereSena": false,
                  "requiereTelefonoVerificado": false
                }
                """;

        mockMvc.perform(put("/api/v1/establecimientos/" + establecimiento.getId())
                        .header("Authorization", "Bearer " + tokenPara(dueno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonSinClaveServicios))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicios", containsInAnyOrder("PARRILLA", "DUCHAS")));
    }

    @Test
    @DisplayName("dueno_ActualizaConServiciosVacio_LosBorra")
    void dueno_ActualizaConServiciosVacio_LosBorra() throws Exception {
        Usuario dueno = crearDueno("dueno-servicios-3@test.com");
        Establecimiento establecimiento = crearEstablecimiento(dueno, "servicios-test-3",
                Set.of(Servicio.PARRILLA, Servicio.DUCHAS));

        EstablecimientoRequest request = new EstablecimientoRequest(
                "Complejo Editado",
                "Calle Falsa 123",
                -34.6,
                -58.4,
                false,
                false,
                null,
                Set.of()
        );

        mockMvc.perform(put("/api/v1/establecimientos/" + establecimiento.getId())
                        .header("Authorization", "Bearer " + tokenPara(dueno))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicios").isEmpty());

        mockMvc.perform(get("/api/v1/publico/complejos/servicios-test-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicios").isEmpty());
    }

    private Usuario crearDueno(String email) {
        return usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());
    }

    private Establecimiento crearEstablecimiento(Usuario dueno, String slug, Set<Servicio> servicios) {
        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle Falsa 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .servicios(new HashSet<>(servicios))
                .build());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
