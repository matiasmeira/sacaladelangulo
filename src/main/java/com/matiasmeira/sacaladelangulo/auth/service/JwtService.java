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
import java.util.Map;

/**
 * Servicio responsable de crear y validar tokens JWT.
 */
@Service
public class JwtService {

    /**
     * HS256 requiere una clave de al menos 256 bits (32 bytes). Se valida en el arranque
     * para evitar que la aplicación levante con un secreto débil o mal configurado.
     */
    private static final int MIN_SECRET_LENGTH_BYTES = 32;

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
        return Jwts.builder()
                .setClaims(extraClaims)
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
        return claims.getSubject().equals(userDetails.getUsername())
                && claims.getExpiration().after(Date.from(Instant.now()));
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
