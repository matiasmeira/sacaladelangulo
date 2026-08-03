package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Valida contra una base real (H2) los finders de marketing (ver Fase 6): el token de
 * baja resuelve al usuario correcto, y el listado de usuarios con opt-in excluye a los
 * que no lo tienen, mismo patrón que UsuarioRepositoryFinPruebaTest.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UsuarioRepository - Finders de marketing (ver Fase 6)")
class UsuarioRepositoryMarketingTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("findByUnsubscribeToken_TokenExistente_DevuelveElUsuarioCorrespondiente")
    void findByUnsubscribeToken_TokenExistente_DevuelveElUsuarioCorrespondiente() {
        Usuario usuario = entityManager.persist(usuarioDePrueba("con-token@test.com", true, "token-abc"));
        entityManager.persist(usuarioDePrueba("otro@test.com", true, "token-xyz"));
        entityManager.flush();

        Optional<Usuario> resultado = usuarioRepository.findByUnsubscribeToken("token-abc");

        assertTrue(resultado.isPresent());
        assertEquals(usuario.getId(), resultado.get().getId());
    }

    @Test
    @DisplayName("findByUnsubscribeToken_TokenInexistente_DevuelveVacio")
    void findByUnsubscribeToken_TokenInexistente_DevuelveVacio() {
        entityManager.persist(usuarioDePrueba("con-token@test.com", true, "token-abc"));
        entityManager.flush();

        Optional<Usuario> resultado = usuarioRepository.findByUnsubscribeToken("token-que-no-existe");

        assertFalse(resultado.isPresent());
    }

    @Test
    @DisplayName("findByAceptaMarketingTrue_SoloDevuelveUsuariosConOptIn")
    void findByAceptaMarketingTrue_SoloDevuelveUsuariosConOptIn() {
        Usuario conOptIn = entityManager.persist(usuarioDePrueba("con-optin@test.com", true, "token-1"));
        entityManager.persist(usuarioDePrueba("sin-optin@test.com", false, "token-2"));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByAceptaMarketingTrue(Pageable.ofSize(50));

        assertEquals(1, resultado.getTotalElements());
        assertEquals(conOptIn.getId(), resultado.getContent().get(0).getId());
    }

    private Usuario usuarioDePrueba(String email, boolean aceptaMarketing, String unsubscribeToken) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario de prueba")
                .rol(Role.PLAYER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .aceptaMarketing(aceptaMarketing)
                .unsubscribeToken(unsubscribeToken)
                .build();
    }
}
