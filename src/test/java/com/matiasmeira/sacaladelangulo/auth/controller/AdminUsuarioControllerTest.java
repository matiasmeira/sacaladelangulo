package com.matiasmeira.sacaladelangulo.auth.controller;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-admin-usuarios;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("DELETE /api/v1/admin/usuarios/{id}")
class AdminUsuarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("admin_EliminaCuentaDeJugador_Devuelve204")
    void admin_EliminaCuentaDeJugador_Devuelve204() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador@admin-usuarios-test.com")
                .password("hash")
                .nombre("Jugador Test")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNotNull(recargado.getDeletedAt());
        assertEquals("Usuario eliminado", recargado.getNombre());
    }

    @Test
    @DisplayName("noAdmin_Devuelve403")
    void noAdmin_Devuelve403() throws Exception {
        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario jugador = usuarioRepository.save(Usuario.builder()
                .email("jugador2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Jugador Test 2")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", "Bearer " + tokenPara(owner)))
                .andExpect(status().isForbidden());

        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        assertNull(recargado.getDeletedAt());
    }

    @Test
    @DisplayName("targetEmployee_Devuelve400")
    void targetEmployee_Devuelve400() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 2")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario empleado = usuarioRepository.save(Usuario.builder()
                .email("empleado@admin-usuarios-test.com")
                .password("hash")
                .nombre("Empleado Test")
                .rol(Role.EMPLOYEE)
                .isActive(true)
                .emailVerified(false)
                .telefonoVerificado(false)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + empleado.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetOwnerConEstablecimientoActivoSinForzar_Devuelve400")
    void targetOwnerConEstablecimientoActivoSinForzar_Devuelve400() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin3@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 3")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner2@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test 2")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Activo")
                .direccion("Calle Falsa 123")
                .slug("complejo-activo-admin-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(owner)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + owner.getId())
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("targetOwnerConEstablecimientoActivoForzando_Devuelve204YNoDesactivaElComplejo")
    void targetOwnerConEstablecimientoActivoForzando_Devuelve204YNoDesactivaElComplejo() throws Exception {
        Usuario admin = usuarioRepository.save(Usuario.builder()
                .email("admin4@admin-usuarios-test.com")
                .password("hash")
                .nombre("Admin Test 4")
                .rol(Role.ADMIN)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Usuario owner = usuarioRepository.save(Usuario.builder()
                .email("owner3@admin-usuarios-test.com")
                .password("hash")
                .nombre("Owner Test 3")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Forzado")
                .direccion("Calle Falsa 456")
                .slug("complejo-forzado-admin-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .dueno(owner)
                .build());

        mockMvc.perform(delete("/api/v1/admin/usuarios/" + owner.getId() + "?forzar=true")
                        .header("Authorization", "Bearer " + tokenPara(admin)))
                .andExpect(status().isNoContent());

        Usuario ownerRecargado = usuarioRepository.findById(owner.getId()).orElseThrow();
        assertNotNull(ownerRecargado.getDeletedAt());

        Establecimiento establecimientoRecargado = establecimientoRepository.findById(establecimiento.getId()).orElseThrow();
        assertEquals(true, establecimientoRecargado.getIsActive());
    }

    private String tokenPara(Usuario usuario) {
        return jwtService.generateToken(UsuarioUserDetailsMapper.map(usuario));
    }
}
