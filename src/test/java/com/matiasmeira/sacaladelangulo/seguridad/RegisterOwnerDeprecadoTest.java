package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/auth/register/owner: deprecado. Creaba dueños sin verificar el email; el alta de
 * dueño pasa por /registro/iniciar con tipo=DUENO. La ruta sigue mapeada para devolver un 410 que
 * apunte al flujo nuevo, sin crear nada ni validar el body.
 */
@DisplayName("POST /api/v1/auth/register/owner (deprecado)")
class RegisterOwnerDeprecadoTest extends AbstractSecurityWebTest {

    private static final String RUTA = "/api/v1/auth/register/owner";
    private static final String EMAIL = "dueno-deprecado@test.com";

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("post_conBodyValido_devuelve410ConMensajeDeMigracion_YNoCreaUsuario")
    void post_conBodyValido_devuelve410ConMensajeDeMigracion_YNoCreaUsuario() throws Exception {
        mockMvc.perform(post(RUTA).with(desdeIp(ipUnica())).contentType("application/json")
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"Password123\",\"nombre\":\"Carlos\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("/api/v1/auth/registro/iniciar"),
                        org.hamcrest.Matchers.containsString("tipo=DUENO"))));

        assertFalse(usuarioRepository.existsByEmail(EMAIL));
    }

    @Test
    @DisplayName("post_sinBody_devuelve410_SinPasarPorLaValidacion")
    void post_sinBody_devuelve410_SinPasarPorLaValidacion() throws Exception {
        mockMvc.perform(post(RUTA).with(desdeIp(ipUnica())))
                .andExpect(status().isGone());
    }
}
