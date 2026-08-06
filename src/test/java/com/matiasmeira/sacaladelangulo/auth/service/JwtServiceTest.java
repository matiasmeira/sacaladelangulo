package com.matiasmeira.sacaladelangulo.auth.service;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtService no tenía ningún test dedicado (verificado: no existe JwtServiceTest en el repo
 * antes de este archivo). Es el corazón de la revocación de sesión (tokenVersion) que
 * respalda "reset de contraseña invalida tokens viejos" — acá se prueba directo, sin pasar
 * por AuthService/RecuperacionPasswordService, que solo prueban que tokenVersion se
 * incrementa, no que JwtService realmente lo use para invalidar.
 */
@DisplayName("JwtService - Firma, expiración y revocación por tokenVersion")
class JwtServiceTest {

    private static final String SECRET_VALIDO = "0123456789abcdef0123456789abcdef01234567"; // 41 bytes

    private JwtService jwtService(long expirationMillis) {
        return new JwtService(SECRET_VALIDO, expirationMillis);
    }

    private UsuarioPrincipal principal(String username, int tokenVersion) {
        return new UsuarioPrincipal(username, "hash", true, List.of(), tokenVersion);
    }

    @Test
    @DisplayName("Constructor rechaza un secreto de menos de 32 bytes")
    void constructor_SecretoCorto_Rechaza() {
        assertThrows(IllegalStateException.class, () -> new JwtService("muy-corto", 3600000L));
    }

    @Test
    @DisplayName("Un token recién generado es válido para el mismo usuario y tokenVersion")
    void isTokenValid_TokenFresco_Valido() {
        JwtService service = jwtService(3600000L);
        UsuarioPrincipal usuario = principal("jugador@test.com", 0);
        String token = service.generateToken(usuario);

        assertTrue(service.isTokenValid(token, usuario));
        assertEquals("jugador@test.com", service.extractUsername(token));
    }

    /**
     * El caso central del checklist de Auth: "tras el reset, un token viejo queda inválido
     * (tokenVersion)". Se simula emitiendo un token con tokenVersion=0 y luego validándolo
     * contra un UsuarioPrincipal con tokenVersion=1 (como si, entre medio, hubiera habido un
     * logout/reset de contraseña/cambio de PIN que incrementó el campo en la entidad).
     */
    @Test
    @DisplayName("Token emitido con tokenVersion viejo queda inválido tras incrementarla (logout/reset/cambio de PIN)")
    void isTokenValid_TokenVersionDesactualizada_Invalido() {
        JwtService service = jwtService(3600000L);
        UsuarioPrincipal usuarioAlEmitir = principal("jugador@test.com", 0);
        String token = service.generateToken(usuarioAlEmitir);

        UsuarioPrincipal usuarioActual = principal("jugador@test.com", 1);
        assertFalse(service.isTokenValid(token, usuarioActual));
    }

    @Test
    @DisplayName("Token válido para un username distinto del que figura en el subject es inválido")
    void isTokenValid_UsernameDistinto_Invalido() {
        JwtService service = jwtService(3600000L);
        String token = service.generateToken(principal("jugador@test.com", 0));

        assertFalse(service.isTokenValid(token, principal("otro@test.com", 0)));
    }

    /**
     * Documenta el comportamiento REAL (no necesariamente el ideal): isTokenValid no
     * "atrapa" la expiración y devuelve false, sino que el parseo interno de jjwt tira
     * ExpiredJwtException. En producción esto es inofensivo porque JwtAuthenticationFilter
     * siempre llama antes a extractUsername (que sí está en su propio try/catch) sobre el
     * mismo token, así que un token expirado nunca llega a pasar por isTokenValid. Pero como
     * método standalone, "isTokenValid" no es realmente boolean-safe: cualquier código nuevo
     * que lo invoque directo sin ese try/catch previo (como hace este test) se lleva una
     * excepción en vez de un false. Ver REVISION_FUNCIONAL.md.
     */
    @Test
    @DisplayName("BUG DE CONTRATO: isTokenValid tira ExpiredJwtException en vez de devolver false para un token expirado")
    void isTokenValid_TokenExpirado_TiraExcepcionEnVezDeFalse() {
        JwtService service = jwtService(-1000L); // expiración en el pasado inmediato
        UsuarioPrincipal usuario = principal("jugador@test.com", 0);
        String token = service.generateToken(usuario);

        assertThrows(ExpiredJwtException.class, () -> service.isTokenValid(token, usuario));
    }
}
