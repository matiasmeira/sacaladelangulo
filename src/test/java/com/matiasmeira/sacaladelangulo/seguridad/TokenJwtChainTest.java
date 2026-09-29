package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.service.UsuarioUserDetailsMapper;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tokens rechazados por la chain completa (JwtAuthenticationFilter + JwtService reales),
 * contra GET /api/v1/usuarios/me, un endpoint autenticado cualquiera. Un token que el filtro
 * no acepta deja la request sin autenticar y la chain responde 401 (RestAuthenticationEntryPoint).
 */
@DisplayName("Token JWT a través de la chain")
class TokenJwtChainTest extends AbstractSecurityWebTest {

    private static final String ME = "/api/v1/usuarios/me";

    private ResultActions pedirMe(String token) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", "Bearer " + token));
    }

    private void assert401(ResultActions resultado) throws Exception {
        resultado.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
    }

    @Test
    @DisplayName("tokenValido_Devuelve200")
    void tokenValido_Devuelve200() throws Exception {
        pedirMe(tokenPara(jugador)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("firmaInvalida_Devuelve401")
    void firmaInvalida_Devuelve401() throws Exception {
        String otraClave = "OtraClaveDistintaQueTambienEsSuficientementeLarga123456";
        String token = Jwts.builder()
                .setSubject(jugador.getEmail())
                .claim("tokenVersion", 0)
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(otraClave.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();

        assert401(pedirMe(token));
    }

    @Test
    @DisplayName("tokenVencido_Devuelve401")
    void tokenVencido_Devuelve401() throws Exception {
        // Mismo usuario y mismo tokenVersion: lo único inválido es la expiración.
        String vencido = jwtService.generateToken(UsuarioUserDetailsMapper.map(jugador), Map.of(), -60_000L);

        assert401(pedirMe(vencido));
    }

    @Test
    @DisplayName("tokenVersionViejo_Devuelve401")
    void tokenVersionViejo_Devuelve401() throws Exception {
        String token = tokenPara(jugador);
        pedirMe(token).andExpect(status().isOk());

        // Lo que hacen el logout y el cambio de contraseña: subir la versión del usuario.
        Usuario recargado = usuarioRepository.findById(jugador.getId()).orElseThrow();
        recargado.setTokenVersion(recargado.getTokenVersion() + 1);
        usuarioRepository.save(recargado);

        assert401(pedirMe(token));
    }

    @Test
    @DisplayName("usuarioEliminado_Devuelve401")
    void usuarioEliminado_Devuelve401() throws Exception {
        String tokenDelJugador = tokenPara(jugador);
        pedirMe(tokenDelJugador).andExpect(status().isOk());

        // Baja real por el endpoint de admin (anonimiza el email, así que el subject deja de resolver).
        mockMvc.perform(delete("/api/v1/admin/usuarios/" + jugador.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        assert401(pedirMe(tokenDelJugador));
    }
}
