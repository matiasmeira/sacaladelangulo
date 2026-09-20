package com.matiasmeira.sacaladelangulo.core.util;

/**
 * Normaliza y valida el FORMATO de un CUIT (11 dígitos + dígito verificador módulo 11). No
 * decide si el CUIT es obligatorio ni en qué momento -- eso es responsabilidad del servicio de
 * solicitud de verificación. Sin dependencias de Spring ni de repositorios: funciones puras.
 */
public final class CuitUtils {

    private static final int LONGITUD_CUIT = 11;
    private static final int[] MULTIPLICADORES = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private CuitUtils() {
    }

    /**
     * Deja sólo los dígitos de la entrada (acepta guiones u otro formato alrededor, p. ej.
     * "20-12345678-6"). No valida longitud ni dígito verificador -- eso lo hace esValido.
     */
    public static String normalizar(String cuit) {
        if (cuit == null) {
            return null;
        }
        return cuit.replaceAll("\\D", "");
    }

    /**
     * true si, tras normalizar, la entrada son exactamente 11 dígitos cuyo dígito verificador
     * (posición 11) coincide con el módulo 11 calculado sobre los primeros 10.
     */
    public static boolean esValido(String cuit) {
        String normalizado = normalizar(cuit);
        if (normalizado == null || normalizado.length() != LONGITUD_CUIT) {
            return false;
        }

        int suma = 0;
        for (int i = 0; i < MULTIPLICADORES.length; i++) {
            suma += Character.getNumericValue(normalizado.charAt(i)) * MULTIPLICADORES[i];
        }

        int digitoVerificadorCalculado = 11 - (suma % 11);
        if (digitoVerificadorCalculado == 11) {
            // resto 0 -> el dígito verificador real es 0, no 11.
            digitoVerificadorCalculado = 0;
        } else if (digitoVerificadorCalculado == 10) {
            // Esta combinación no se emite en Argentina: NO se mapea a 9 ni a ningún otro
            // valor, es directamente un CUIT inválido.
            return false;
        }

        int digitoVerificadorReal = Character.getNumericValue(normalizado.charAt(10));
        return digitoVerificadorCalculado == digitoVerificadorReal;
    }
}
