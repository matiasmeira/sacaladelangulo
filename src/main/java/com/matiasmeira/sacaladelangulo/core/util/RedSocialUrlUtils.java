package com.matiasmeira.sacaladelangulo.core.util;

import java.util.Set;

/**
 * Valida (de forma permisiva) que una URL sea un perfil de Instagram o Facebook. Solo mira
 * el formato del host -- con o sin esquema, con o sin "www.", con o sin path/barra final --
 * no resuelve el link ni valida que el perfil exista: eso lo termina revisando a mano el
 * admin que procesa la verificación manual. Ante la duda, la política es aceptar: un falso
 * rechazo acá es un dueño que no puede completar el alta. Sin dependencias de Spring, mismo
 * estilo que CuitUtils.
 */
public final class RedSocialUrlUtils {

    private static final Set<String> DOMINIOS_VALIDOS = Set.of("instagram.com", "facebook.com", "fb.com");

    private RedSocialUrlUtils() {
    }

    /**
     * true si, tras sacar un esquema http(s) opcional y un "www." opcional, el host es
     * exactamente instagram.com, facebook.com o fb.com. No exige que haya un path después
     * del host (alcanza con pegar el dominio solo) ni le importa si termina en "/".
     */
    public static boolean esUrlValida(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String sinEsquema = url.trim().replaceFirst("(?i)^https?://", "");
        String host = sinEsquema.split("[/?#]", 2)[0];
        String hostNormalizado = host.replaceFirst("(?i)^www\\.", "").toLowerCase();
        return DOMINIOS_VALIDOS.contains(hostNormalizado);
    }
}
