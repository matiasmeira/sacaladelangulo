package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvisoFinPruebaEmailListener - Envío del email de fin de prueba")
class AvisoFinPruebaEmailListenerTest {

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private EmailRenderer emailRenderer;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private AvisoFinPruebaEmailListener listener;

    @Test
    @DisplayName("enviarAvisoFinPrueba_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail")
    void enviarAvisoFinPrueba_UsuarioExistente_RenderizaLaPlantillaYEnviaElEmail() {
        Usuario usuario = Usuario.builder()
                .email("jugador@test.com")
                .password("hash")
                .nombre("Juan")
                .build();
        usuario.setId(10L);
        when(usuarioRepository.findById(10L)).thenReturn(Optional.of(usuario));
        when(emailRenderer.render(eq("fin-prueba"), anyMap())).thenReturn("<html>fin-prueba</html>");

        listener.enviarAvisoFinPrueba(new AvisoFinPruebaEvent(10L, 3));

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("fin-prueba"), modeloCaptor.capture());
        assertEquals("Juan", modeloCaptor.getValue().get("nombre"));
        assertEquals(3, modeloCaptor.getValue().get("diasRestantes"));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu prueba gratuita termina en 3 días"), eq("<html>fin-prueba</html>"));
    }

    @Test
    @DisplayName("enviarAvisoFinPrueba_UnDiaRestante_UsaElAsuntoEnSingular")
    void enviarAvisoFinPrueba_UnDiaRestante_UsaElAsuntoEnSingular() {
        Usuario usuario = Usuario.builder()
                .email("jugador@test.com")
                .password("hash")
                .nombre("Juan")
                .build();
        usuario.setId(11L);
        when(usuarioRepository.findById(11L)).thenReturn(Optional.of(usuario));
        when(emailRenderer.render(eq("fin-prueba"), anyMap())).thenReturn("<html>fin-prueba</html>");

        listener.enviarAvisoFinPrueba(new AvisoFinPruebaEvent(11L, 1));

        verify(emailService).enviar(eq("jugador@test.com"), eq("Tu prueba gratuita termina en 1 día"), eq("<html>fin-prueba</html>"));
    }

    @Test
    @DisplayName("enviarAvisoFinPrueba_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion")
    void enviarAvisoFinPrueba_UsuarioNoEncontrado_NoEnviaEmailNiLanzaExcepcion() {
        when(usuarioRepository.findById(99L)).thenReturn(Optional.empty());

        listener.enviarAvisoFinPrueba(new AvisoFinPruebaEvent(99L, 7));

        verify(emailService, never()).enviar(anyString(), anyString(), anyString());
        verifyNoInteractions(emailRenderer);
    }
}
