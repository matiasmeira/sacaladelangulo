package com.matiasmeira.sacaladelangulo.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Servicio responsable de crear y validar tokens JWT.
 *
 * <p>Usa jjwt 0.11.5 (ver {@code pom.xml}), desactualizado respecto a la línea 0.12.x
 * (ver B14 en la auditoría). Pendiente: planificar el upgrade coordinado con el dominio
 * auth — 0.12.x reemplaza esta API por una fluida distinta ({@code Jwts.parserBuilder()}
 * → {@code Jwts.parser()}, {@code setSigningKey} → {@code verifyWith}, etc.), así que no
 * es un simple bump de versión en el pom. Se decidió no encararlo junto con B3 (que
 * agregó el claim tokenVersion a este mismo archivo) para no acumular dos cambios de
 * riesgo distinto sobre el código de autenticación en el mismo momento.
 */
@Service
public class JwtService {

    /**
     * HS256 requiere una clave de al menos 256 bits (32 bytes). Se valida en el arranque
     * para evitar que la aplicación levante con un secreto débil o mal configurado.
     */
    private static final int MIN_SECRET_LENGTH_BYTES = 32;

    /**
     * Claim con la versión de token del Usuario al momento de emitirlo. Permite revocar
     * tokens ya emitidos (logout, cambio de contraseña/PIN) sin esperar a que expiren:
     * ver isTokenValid y B3 en la auditoría. Los tokens emitidos antes de este cambio no
     * llevan el claim y quedan invalidados en la primera petición tras el deploy.
     */
    private static final String TOKEN_VERSION_CLAIM = "tokenVersion";

    private final SecretKey signingKey;
    private final long jwtExpirationMillis;

    public JwtService(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.expiration-millis:3600000}") long jwtExpirationMillis
    ) {
        byte[] secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret debe tener al menos " + MIN_SECRET_LENGTH_BYTES + " bytes para firmar con HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.jwtExpirationMillis = jwtExpirationMillis;
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(userDetails, Map.of(), jwtExpirationMillis);
    }

    /**
     * Genera un token con claims adicionales y una expiración propia (distinta de la
     * default), usado para sesiones de empleado de mostrador: viven mucho menos que
     * una sesión normal y llevan el ID del empleado para que el frontend lo muestre
     * sin pegarle a otro endpoint.
     */
    public String generateToken(UserDetails userDetails, Map<String, Object> extraClaims, long expirationMillis) {
        Map<String, Object> claims = new HashMap<>(extraClaims);
        if (userDetails instanceof UsuarioPrincipal principal) {
            claims.put(TOKEN_VERSION_CLAIM, principal.getTokenVersion());
        }
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userDetails.getUsername())
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plusMillis(expirationMillis)))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        // Un solo parseo/verificación de firma para las dos condiciones, en vez de
        // parsear el token dos veces (una por extractUsername, otra por isTokenExpired).
        Claims claims = extractAllClaims(token);
        boolean valido = claims.getSubject().equals(userDetails.getUsername())
                && claims.getExpiration().after(Date.from(Instant.now()));
        if (valido && userDetails instanceof UsuarioPrincipal principal) {
            Integer tokenVersion = claims.get(TOKEN_VERSION_CLAIM, Integer.class);
            valido = tokenVersion != null && tokenVersion == principal.getTokenVersion();
        }
        return valido;
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
