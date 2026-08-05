package com.matiasmeira.sacaladelangulo.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hash SHA-256 (rápido y determinístico, a diferencia de BCrypt) para secretos opacos de
 * corta vida que se buscan por igualdad exacta: tokens/códigos de verificación de email y
 * recuperación de contraseña (ver M-05 en la auditoría). Mismo algoritmo que ya usaba
 * DispositivoCajaService para el token de dispositivo de caja, extraído acá para no
 * triplicarlo en los tres servicios de auth/ que lo necesitan ahora.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }
}
