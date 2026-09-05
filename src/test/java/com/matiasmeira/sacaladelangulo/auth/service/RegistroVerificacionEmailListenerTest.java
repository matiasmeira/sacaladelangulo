package com.matiasmeira.sacaladelangulo.auth.service;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistroVerificacionEmailListener - Envío de emails de registro")
class RegistroVerificacionEmailListenerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private EmailRenderer emailRenderer;

    @InjectMocks
    private RegistroVerificacionEmailListener listener;

    @Test
    @DisplayName("enviarEmailDeVerificacion_RenderizaLaPlantillaConLinkYCodigoYEnviaAlEmailDelEvento")
    void enviarEmailDeVerificacion_RenderizaLaPlantillaConLinkYCodigoYEnviaAlEmailDelEvento() {
        VerificacionEmailSolicitadaEvent evento = new VerificacionEmailSolicitadaEvent(
                "nuevo@test.com", "http://localhost:5173/verificar?token=abc", "123456");
        when(emailRenderer.render(eq("verificacion"), anyMap())).thenReturn("<html>verificacion</html>");

        listener.enviarEmailDeVerificacion(evento);

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("verificacion"), modeloCaptor.capture());
        assertEquals("http://localhost:5173/verificar?token=abc", modeloCaptor.getValue().get("linkVerificacion"));
        assertEquals("123456", modeloCaptor.getValue().get("codigo"));

        verify(emailService).enviar(eq("nuevo@test.com"), eq("Verificá tu cuenta"), eq("<html>verificacion</html>"));
    }

    @Test
    @DisplayName("enviarEmailDeBienvenida_RenderizaLaPlantillaConNombreYEnviaAlEmailDelEvento")
    void enviarEmailDeBienvenida_RenderizaLaPlantillaConNombreYEnviaAlEmailDelEvento() {
        RegistroCompletadoEvent evento = new RegistroCompletadoEvent("nuevo@test.com", "Juan");
        when(emailRenderer.render(eq("bienvenida"), anyMap())).thenReturn("<html>bienvenida</html>");

        listener.enviarEmailDeBienvenida(evento);

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("bienvenida"), modeloCaptor.capture());
        assertEquals("Juan", modeloCaptor.getValue().get("nombre"));

        verify(emailService).enviar(eq("nuevo@test.com"), eq("¡Bienvenido a Canchear!"), eq("<html>bienvenida</html>"));
    }
}
