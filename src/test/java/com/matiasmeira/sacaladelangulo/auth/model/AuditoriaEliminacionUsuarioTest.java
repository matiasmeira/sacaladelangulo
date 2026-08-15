package com.matiasmeira.sacaladelangulo.auth.model;

import com.matiasmeira.sacaladelangulo.auth.repository.AuditoriaEliminacionUsuarioRepository;
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
        "spring.datasource.url=jdbc:h2:mem:testdb-auditoria-eliminacion;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("AuditoriaEliminacionUsuario - persistencia")
class AuditoriaEliminacionUsuarioTest {

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private AuditoriaEliminacionUsuarioRepository auditoriaEliminacionUsuarioRepository;

    @Test
    @DisplayName("guardarConActorId_SeRecuperaTipoYDetalleAlReleer")
    void guardarConActorId_SeRecuperaTipoYDetalleAlReleer() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("auditoria-eliminacion-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .build());

        AuditoriaEliminacionUsuario registro = auditoriaEliminacionUsuarioRepository.save(
                AuditoriaEliminacionUsuario.builder()
                        .usuario(usuario)
                        .actorId(99L)
                        .tipo(TipoEliminacionCuenta.ELIMINACION_ADMIN)
                        .detalle("Forzado: 1 establecimiento(s) activo(s) sin desactivar")
                        .fechaHora(LocalDateTime.now())
                        .build());

        AuditoriaEliminacionUsuario recargado = auditoriaEliminacionUsuarioRepository.findById(registro.getId()).orElseThrow();

        assertEquals(usuario.getId(), recargado.getUsuario().getId());
        assertEquals(99L, recargado.getActorId());
        assertEquals(TipoEliminacionCuenta.ELIMINACION_ADMIN, recargado.getTipo());
        assertEquals("Forzado: 1 establecimiento(s) activo(s) sin desactivar", recargado.getDetalle());
    }

    @Test
    @DisplayName("guardarSinActorId_QuedaNull")
    void guardarSinActorId_QuedaNull() {
        Usuario usuario = usuarioRepository.save(Usuario.builder()
                .email("auditoria-eliminacion-self-test@test.com")
                .password("hash")
                .nombre("Usuario eliminado")
                .rol(Role.PLAYER)
                .isActive(false)
                .build());

        AuditoriaEliminacionUsuario registro = auditoriaEliminacionUsuarioRepository.save(
                AuditoriaEliminacionUsuario.builder()
                        .usuario(usuario)
                        .tipo(TipoEliminacionCuenta.AUTOELIMINACION)
                        .fechaHora(LocalDateTime.now())
                        .build());

        AuditoriaEliminacionUsuario recargado = auditoriaEliminacionUsuarioRepository.findById(registro.getId()).orElseThrow();

        assertNull(recargado.getActorId());
        assertNull(recargado.getDetalle());
    }
}
