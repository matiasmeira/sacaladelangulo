package com.matiasmeira.sacaladelangulo.core.util;

/**
 * Los servicios que arman links a partir de app.frontend-url lo hacen concatenando
 * directamente un path que ya empieza con "/" (ver RegistroVerificacionService,
 * RecuperacionPasswordService, DispositivoCajaService, OfertaMarketingBatchSender). Si esa
 * property viene con un "/" final, el link resultante queda con "//" y puede romper el
 * ruteo del front. Se extrae acá (en vez de repetir el chequeo en los 4 call sites) porque
 * es la misma normalización en los cuatro casos.
 */
public final class UrlUtils {

    private UrlUtils() {
    }

    public static String quitarSlashFinal(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final int RUTA_INTERNA_MAX_LARGO = 500;

    /**
     * Valida mínimamente que un valor enviado por el cliente sea una ruta interna del front
     * (para usarla como "volver a"): empieza con "/", no con "//" ni con "/" + barra invertida
     * (que un navegador interpreta como otro host), no tiene caracteres de control y mide
     * como máximo 500 caracteres.
     */
    public static boolean esRutaInternaSegura(String ruta) {
        if (ruta == null || ruta.isEmpty() || ruta.length() > RUTA_INTERNA_MAX_LARGO) {
            return false;
        }
        if (ruta.charAt(0) != '/') {
            return false;
        }
        if (ruta.startsWith("//") || ruta.startsWith("/\\")) {
            return false;
        }
        return ruta.chars().noneMatch(Character::isISOControl);
    }
}
