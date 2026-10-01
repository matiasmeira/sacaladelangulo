package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.TokenVerificacionEmail;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prueba la migración V28 (columna rol en tokens_verificacion_email) contra Postgres real
 * con Flyway: el rol se guarda y se lee, y un token sin rol explícito queda como PLAYER.
 */
@Tag("testcontainers")
@DisplayName("TokenVerificacionEmailRepository - columna rol (V28) contra Postgres real")
class TokenVerificacionEmailRepositoryPostgresIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TokenVerificacionEmailRepository repository;

    private TokenVerificacionEmail.TokenVerificacionEmailBuilder base(String sufijo) {
        return TokenVerificacionEmail.builder()
                .email("token-rol-" + sufijo + "-" + System.nanoTime() + "@test.com")
                .tokenHash("hash-token-" + sufijo + "-" + System.nanoTime())
                .codigoHash("hash-codigo-" + sufijo)
                .fechaExpiracion(LocalDateTime.now().plusMinutes(15));
    }

    @Test
    @DisplayName("guardarYLeer_RolOwner_SePersisteYSeRecuperaPorTokenHash")
    void guardarYLeer_RolOwner_SePersisteYSeRecuperaPorTokenHash() {
        TokenVerificacionEmail guardado = repository.saveAndFlush(base("owner").rol(Role.OWNER).build());

        TokenVerificacionEmail leido = repository.findByTokenHash(guardado.getTokenHash()).orElseThrow();
        assertEquals(Role.OWNER, leido.getRol());
    }

    @Test
    @DisplayName("guardarYLeer_SinRolExplicito_QuedaComoPlayer")
    void guardarYLeer_SinRolExplicito_QuedaComoPlayer() {
        TokenVerificacionEmail guardado = repository.saveAndFlush(base("default").build());

        TokenVerificacionEmail leido = repository.findByEmail(guardado.getEmail()).orElseThrow();
        assertEquals(Role.PLAYER, leido.getRol());
    }
}
