package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Genera el slug público de un establecimiento a partir de su nombre: normaliza
 * acentos, lo pasa a minúsculas y lo separa por guiones. Si el slug base ya existe
 * (otro complejo con nombre igual o muy similar), le agrega un sufijo numérico
 * incremental hasta encontrar uno libre (ver FASE 1 del contrato de zona pública).
 */
@Component
@RequiredArgsConstructor
public class SlugGenerator {

    private static final Pattern DIACRITICOS = Pattern.compile("\\p{M}");
    private static final Pattern NO_ALFANUMERICO = Pattern.compile("[^a-z0-9]+");
    private static final Pattern GUIONES_BORDE = Pattern.compile("^-+|-+$");

    private final EstablecimientoRepository establecimientoRepository;

    public String generarSlugUnico(String nombre) {
        String base = normalizar(nombre);
        String candidato = base;
        int sufijo = 1;
        while (establecimientoRepository.existsBySlug(candidato)) {
            sufijo++;
            candidato = base + "-" + sufijo;
        }
        return candidato;
    }

    private String normalizar(String nombre) {
        String descompuesto = Normalizer.normalize(nombre.toLowerCase(), Normalizer.Form.NFD);
        String sinAcentos = DIACRITICOS.matcher(descompuesto).replaceAll("");
        String slug = NO_ALFANUMERICO.matcher(sinAcentos).replaceAll("-");
        slug = GUIONES_BORDE.matcher(slug).replaceAll("");
        return slug.isEmpty() ? "complejo" : slug;
    }
}
