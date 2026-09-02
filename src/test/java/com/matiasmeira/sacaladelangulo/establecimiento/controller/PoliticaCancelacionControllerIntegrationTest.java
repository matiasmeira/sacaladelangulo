package com.matiasmeira.sacaladelangulo.establecimiento.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-politica-cancelacion;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("GET/PATCH /api/v1/establecimientos/{id}/politicas-cancelacion")
class PoliticaCancelacionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    private Establecimiento sembrarLocal(String sufijo) {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-" + sufijo + "@politica-test.com")
                .password("hash")
                .nombre("Dueno " + sufijo)
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        return establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo " + sufijo)
                .direccion("Calle 123")
                .slug("complejo-politica-" + sufijo)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .horasCancelacionAntesPartido(24)
                .minutosGraciaCancelacion(30)
                .dueno(dueno)
                .build());
    }

    private String duenoDe(Establecimiento establecimiento) {
        return establecimiento.getDueno().getEmail();
    }

    private String sembrarEmpleado(Establecimiento local, String sufijo) {
        String email = "empleado-" + sufijo + "@politica-test.interno";
        usuarioRepository.save(Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Empleado " + sufijo)
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .establecimiento(local)
                .build());
        return email;
    }

    @Test
    @DisplayName("dueno_ObtieneSuPolitica_200")
    void dueno_ObtieneSuPolitica_200() throws Exception {
        Establecimiento establecimiento = sembrarLocal("get-ok");

        mockMvc.perform(get("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(24))
                .andExpect(jsonPath("$.minutosGraciaCancelacion").value(30))
                .andExpect(jsonPath("$.reservasFuturasAfectadas").value(nullValue()));
    }

    @Test
    @DisplayName("dueno_ActualizaPolitica_200_PersisteYDevuelveReservasAfectadas")
    void dueno_ActualizaPolitica_200_PersisteYDevuelveReservasAfectadas() throws Exception {
        Establecimiento establecimiento = sembrarLocal("patch-ok");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horasCancelacionAntesPartido").value(12))
                .andExpect(jsonPath("$.minutosGraciaCancelacion").value(30))
                .andExpect(jsonPath("$.reservasFuturasAfectadas").value(0));

        Establecimiento actualizado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(12, actualizado.getHorasCancelacionAntesPartido());
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Get_403")
    void ownerDeOtroEstablecimiento_Get_403() throws Exception {
        Establecimiento propio = sembrarLocal("idor-propio");
        Establecimiento ajeno = sembrarLocal("idor-ajeno");

        mockMvc.perform(get("/api/v1/establecimientos/" + ajeno.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(propio)).roles("OWNER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ownerDeOtroEstablecimiento_Patch_403")
    void ownerDeOtroEstablecimiento_Patch_403() throws Exception {
        Establecimiento propio = sembrarLocal("idor-patch-propio");
        Establecimiento ajeno = sembrarLocal("idor-patch-ajeno");

        mockMvc.perform(patch("/api/v1/establecimientos/" + ajeno.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(propio)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("empleadoDelPropioEstablecimiento_Patch_403")
    void empleadoDelPropioEstablecimiento_Patch_403() throws Exception {
        Establecimiento establecimiento = sembrarLocal("empleado-no");
        String emailEmpleado = sembrarEmpleado(establecimiento, "empleado-no");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(emailEmpleado).roles("EMPLOYEE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 12}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("horasNegativas_400")
    void horasNegativas_400() throws Exception {
        Establecimiento establecimiento = sembrarLocal("rango-negativo");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": -1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("horasPorEncimaDe168_400")
    void horasPorEncimaDe168_400() throws Exception {
        Establecimiento establecimiento = sembrarLocal("rango-alto");

        mockMvc.perform(patch("/api/v1/establecimientos/" + establecimiento.getId() + "/politicas-cancelacion")
                        .with(user(duenoDe(establecimiento)).roles("OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horasCancelacionAntesPartido\": 169}"))
                .andExpect(status().isBadRequest());
    }
}
