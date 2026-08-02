package com.matiasmeira.sacaladelangulo.caja.service;

import com.matiasmeira.sacaladelangulo.caja.model.DispositivoCaja;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Punto único de lectura/escritura de la cookie de dispositivo de caja
 * ({@value #COOKIE_NAME}). No es un filtro: solo dos endpoints (login de empleado y
 * listado de nombres activos) necesitan este chequeo, así que se llama explícitamente
 * desde sus controllers en vez de agregar un filtro/interceptor con matching de rutas
 * para un caso tan acotado. No confundir con JwtAuthenticationFilter: ese autentica
 * principals Usuario vía loadUserByUsername, esto valida confianza de dispositivo, un
 * concepto separado que no otorga permisos de negocio.
 */
@Component
@RequiredArgsConstructor
public class DispositivoCajaGate {

    private static final String COOKIE_NAME = "saque_caja_device";

    private final DispositivoCajaService dispositivoCajaService;

    /**
     * Exige una cookie de dispositivo válida y activa; devuelve el dispositivo (con su
     * establecimiento) o lanza AccessDeniedException (403, mismo criterio que
     * validarPropietarioOAdmin en el resto del proyecto).
     */
    public DispositivoCaja exigirDispositivo(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        String tokenCrudo = cookies == null ? null : java.util.Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);

        if (tokenCrudo == null || tokenCrudo.isBlank()) {
            throw new AccessDeniedException("Dispositivo no autorizado");
        }

        return dispositivoCajaService.validarToken(tokenCrudo);
    }

    /**
     * Cross-site por defecto (SameSite=None + Secure): no hay evidencia de un reverse
     * proxy que ponga front y back en el mismo dominio hoy. Si en el futuro se agrega
     * uno, esto puede pasar a SameSite=Lax.
     */
    static void setCookie(HttpServletResponse response, String rawToken, long maxAgeMillis) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("None")
                .path("/")
                .maxAge(Duration.ofMillis(maxAgeMillis))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
