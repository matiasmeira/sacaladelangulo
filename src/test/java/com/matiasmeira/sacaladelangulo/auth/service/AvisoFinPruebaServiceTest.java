package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvisoFinPruebaService - Avisos de fin de prueba gratuita")
class AvisoFinPruebaServiceTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AvisoFinPruebaService service;

    @Test
    @DisplayName("avisarFinDePrueba_UsuarioEnUmbral7_PublicaEventoConDiasRestantesYMarcaFlagYGuarda")
    void avisarFinDePrueba_UsuarioEnUmbral7_PublicaEventoConDiasRestantesYMarcaFlagYGuarda() {
        Usuario usuario = usuarioDePrueba(1L);
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(List.of(usuario));
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());

        service.avisarFinDePrueba();

        ArgumentCaptor<AvisoFinPruebaEvent> eventoCaptor = ArgumentCaptor.forClass(AvisoFinPruebaEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventoCaptor.capture());
        assertEquals(1L, eventoCaptor.getValue().usuarioId());
        assertEquals(7, eventoCaptor.getValue().diasRestantes());

        assertTrue(usuario.getAvisoFinPrueba7Enviado());

        ArgumentCaptor<List<Usuario>> guardadosCaptor = ArgumentCaptor.forClass(List.class);
        verify(usuarioRepository).saveAll(guardadosCaptor.capture());
        assertEquals(1, guardadosCaptor.getValue().size());
        assertEquals(usuario, guardadosCaptor.getValue().get(0));
    }

    @Test
    @DisplayName("avisarFinDePrueba_UsuarioEnUmbral3_PublicaEventoConDiasRestantesYMarcaFlag")
    void avisarFinDePrueba_UsuarioEnUmbral3_PublicaEventoConDiasRestantesYMarcaFlag() {
        Usuario usuario = usuarioDePrueba(2L);
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(List.of(usuario));
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());

        service.avisarFinDePrueba();

        ArgumentCaptor<AvisoFinPruebaEvent> eventoCaptor = ArgumentCaptor.forClass(AvisoFinPruebaEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventoCaptor.capture());
        assertEquals(2L, eventoCaptor.getValue().usuarioId());
        assertEquals(3, eventoCaptor.getValue().diasRestantes());
        assertTrue(usuario.getAvisoFinPrueba3Enviado());
    }

    @Test
    @DisplayName("avisarFinDePrueba_UsuarioEnUmbral1_PublicaEventoConDiasRestantesYMarcaFlag")
    void avisarFinDePrueba_UsuarioEnUmbral1_PublicaEventoConDiasRestantesYMarcaFlag() {
        Usuario usuario = usuarioDePrueba(3L);
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(List.of(usuario));

        service.avisarFinDePrueba();

        ArgumentCaptor<AvisoFinPruebaEvent> eventoCaptor = ArgumentCaptor.forClass(AvisoFinPruebaEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventoCaptor.capture());
        assertEquals(3L, eventoCaptor.getValue().usuarioId());
        assertEquals(1, eventoCaptor.getValue().diasRestantes());
        assertTrue(usuario.getAvisoFinPrueba1Enviado());
    }

    @Test
    @DisplayName("avisarFinDePrueba_SinUsuariosEnNingunUmbral_NoPublicaEventosNiGuarda")
    void avisarFinDePrueba_SinUsuariosEnNingunUmbral_NoPublicaEventosNiGuarda() {
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba3EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());
        when(usuarioRepository.findByFechaFinPruebaBetweenAndAvisoFinPrueba1EnviadoFalseAndDeletedAtIsNull(any(), any()))
                .thenReturn(Collections.emptyList());

        service.avisarFinDePrueba();

        verifyNoInteractions(eventPublisher);
        verify(usuarioRepository, never()).saveAll(anyList());
    }

    private Usuario usuarioDePrueba(Long id) {
        Usuario usuario = Usuario.builder()
                .email("usuario" + id + "@test.com")
                .password("hash")
                .nombre("Usuario " + id)
                .fechaFinPrueba(LocalDateTime.now())
                .build();
        usuario.setId(id);
        return usuario;
    }
}
