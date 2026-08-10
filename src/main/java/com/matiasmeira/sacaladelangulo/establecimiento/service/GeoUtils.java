package com.matiasmeira.sacaladelangulo.establecimiento.service;

/**
 * Cálculo puro de distancia entre dos coordenadas (fórmula de Haversine), usado para
 * mostrarle al visitante la distancia a un complejo en los listados públicos. Mismo radio
 * terrestre (6371 km) que usa el filtro geográfico de
 * EstablecimientoRepository.findCercanosYPorDeporte, para que el número que se muestra sea
 * consistente con el que se usó para filtrar.
 */
public final class GeoUtils {

    private static final double RADIO_TIERRA_KM = 6371.0;

    private GeoUtils() {
    }

    public static double distanciaKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return RADIO_TIERRA_KM * c;
    }
}
