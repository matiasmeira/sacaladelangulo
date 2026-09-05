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
}
