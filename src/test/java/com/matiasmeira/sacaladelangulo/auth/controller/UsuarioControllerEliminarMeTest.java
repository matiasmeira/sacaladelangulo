package com.matiasmeira.sacaladelangulo.auth.controller;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.JwtService;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-eliminar-me;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/usuarios/me")
class UsuarioControllerEliminarMeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("passwordCorrecta_Anonimiza_Y_Devuelve204")
    void passwordCorrecta_Anonimiza_Y_Devuelve204() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Jugador Test")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(jugador))
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isNoContent());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertEquals("deleted+" + jugador.getId() + "@saque.deleted", recargado.getEmail());
        assertEquals("Usuario eliminado", recargado.getNombre());
        assertNotNull(recargado.getDeletedAt());
        assertEquals(false, recargado.getIsActive());
    }

    @Test
    @DisplayName("tokenReutilizadoTrasEliminar_Devuelve401YNo500")
    void tokenReutilizadoTrasEliminar_Devuelve401YNo500() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador3@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Jugador Test 3")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        String token = tokenPara(jugador);

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isNoContent());

        // El mismo token, ahora con un subject (email) que ya no resuelve a ningún usuario
        // (fue anonimizado): debe devolver 401, no un 500 por UsernameNotFoundException
        // escapando del filtro.
        mockMvc.perform(get("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("passwordIncorrecta_Devuelve401YNoModificaLaCuenta")
    void passwordIncorrecta_Devuelve401YNoModificaLaCuenta() throws Exception {
        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador2@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Jugador Test 2")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(jugador))
                        .contentType("application/json")
                        .content("{\"password\":\"Incorrecta\"}"))
                .andExpect(status().isUnauthorized());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNull(recargado.getDeletedAt());
    }

    @Test
    @DisplayName("rolEmployee_Devuelve403")
    void rolEmployee_Devuelve403() throws Exception {
        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@eliminar-me-test.com")
                .password(passwordEncoder.encode("Password123"))
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .header("Authorization", "Bearer " + tokenPara(empleado))
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        mockMvc.perform(delete("/api/v1/usuarios/me")
                        .contentType("application/json")
                        .content("{\"password\":\"Password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
