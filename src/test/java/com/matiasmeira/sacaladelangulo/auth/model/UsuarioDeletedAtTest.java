package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-usuario-deleted-at;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Usuario.deletedAt - persistencia")
class UsuarioDeletedAtTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("guardarConDeletedAt_SeRecuperaElMismoValorAlReleer")
    void guardarConDeletedAt_SeRecuperaElMismoValorAlReleer() {
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("deleted-at-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .deletedAt(ahora)
                .build());

        Usuario recargado = usuarioRepository.findById(usuario.getId()).orElseThrow();

        assertEquals(ahora, recargado.getDeletedAt());
    }

    @Test
    @DisplayName("guardarSinDeletedAt_QuedaNull")
    void guardarSinDeletedAt_QuedaNull() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("sin-deleted-at-test@test.com")
                .password("hash")
                .nombre("Usuario Activo")
                .rol(Role.PLAYER)
                .isActive(true)
                .build());

        Usuario recargado = usuarioRepository.findById(usuario.getId()).orElseThrow();

        assertNull(recargado.getDeletedAt());
    }
}
