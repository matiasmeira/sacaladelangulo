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
@DisplayName("RecuperacionPasswordEmailListener - Envío de emails de recuperación de contraseña")
class RecuperacionPasswordEmailListenerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private EmailRenderer emailRenderer;

    @InjectMocks
    private RecuperacionPasswordEmailListener listener;

    @Test
    @DisplayName("enviarEmailDeRecuperacion_RenderizaLaPlantillaConLinkYCodigoYEnviaAlEmailDelEvento")
    void enviarEmailDeRecuperacion_RenderizaLaPlantillaConLinkYCodigoYEnviaAlEmailDelEvento() {
        RecuperacionPasswordSolicitadaEvent evento = new RecuperacionPasswordSolicitadaEvent(
                "existente@test.com", "http://localhost:5173/restablecer?token=abc", "123456");
        when(emailRenderer.render(eq("recuperar-password"), anyMap())).thenReturn("<html>recuperar-password</html>");

        listener.enviarEmailDeRecuperacion(evento);

        ArgumentCaptor<Map<String, Object>> modeloCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailRenderer).render(eq("recuperar-password"), modeloCaptor.capture());
        assertEquals("http://localhost:5173/restablecer?token=abc", modeloCaptor.getValue().get("linkRecuperacion"));
        assertEquals("123456", modeloCaptor.getValue().get("codigo"));

        verify(emailService).enviar(eq("existente@test.com"), eq("Recuperá tu contraseña"), eq("<html>recuperar-password</html>"));
    }

    @Test
    @DisplayName("enviarEmailDePasswordCambiada_RenderizaLaPlantillaYEnviaAlEmailDelEvento")
    void enviarEmailDePasswordCambiada_RenderizaLaPlantillaYEnviaAlEmailDelEvento() {
        PasswordCambiadaEvent evento = new PasswordCambiadaEvent("existente@test.com");
        when(emailRenderer.render(eq("password-cambiada"), anyMap())).thenReturn("<html>password-cambiada</html>");

        listener.enviarEmailDePasswordCambiada(evento);

        verify(emailRenderer).render(eq("password-cambiada"), anyMap());
        verify(emailService).enviar(eq("existente@test.com"), eq("Tu contraseña fue cambiada"), eq("<html>password-cambiada</html>"));
    }
}
