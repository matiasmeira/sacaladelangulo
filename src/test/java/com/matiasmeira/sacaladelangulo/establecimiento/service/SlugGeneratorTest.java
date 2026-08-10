package com.matiasmeira.sacaladelangulo.establecimiento.service;

import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlugGenerator - Generación de slug único")
class SlugGeneratorTest {

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @InjectMocks
    private SlugGenerator slugGenerator;

    @Test
    @DisplayName("generarSlugUnico_NombreSimple_DevuelveSlugMinusculaConGuiones")
    void generarSlugUnico_NombreSimple_DevuelveSlugMinusculaConGuiones() {
        when(establecimientoRepository.existsBySlug("cancha-norte")).thenReturn(false);

        assertEquals("cancha-norte", slugGenerator.generarSlugUnico("Cancha Norte"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreConAcentosYEnie_NormalizaCaracteres")
    void generarSlugUnico_NombreConAcentosYEnie_NormalizaCaracteres() {
        when(establecimientoRepository.existsBySlug("futbol-5-nunoa")).thenReturn(false);

        assertEquals("futbol-5-nunoa", slugGenerator.generarSlugUnico("Fútbol 5 Ñuñoa"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreDuplicado_AgregaSufijoNumericoHastaEncontrarUnoLibre")
    void generarSlugUnico_NombreDuplicado_AgregaSufijoNumericoHastaEncontrarUnoLibre() {
        when(establecimientoRepository.existsBySlug("cancha-norte")).thenReturn(true);
        when(establecimientoRepository.existsBySlug("cancha-norte-2")).thenReturn(true);
        when(establecimientoRepository.existsBySlug("cancha-norte-3")).thenReturn(false);

        assertEquals("cancha-norte-3", slugGenerator.generarSlugUnico("Cancha Norte"));
    }

    @Test
    @DisplayName("generarSlugUnico_NombreSinCaracteresAlfanumericos_UsaFallbackComplejo")
    void generarSlugUnico_NombreSinCaracteresAlfanumericos_UsaFallbackComplejo() {
        when(establecimientoRepository.existsBySlug("complejo")).thenReturn(false);

        assertEquals("complejo", slugGenerator.generarSlugUnico("!!!"));
    }
}
