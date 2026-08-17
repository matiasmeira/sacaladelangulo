package com.matiasmeira.sacaladelangulo.auth.service;

import com.matiasmeira.sacaladelangulo.core.email.EmailRenderer;
import com.matiasmeira.sacaladelangulo.core.email.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CuentaEliminadaEmailListener - Mail de confirmacion de baja")
class CuentaEliminadaEmailListenerTest {

    @Mock
    private EmailService emailService;

    @Mock
    private EmailRenderer emailRenderer;

    @InjectMocks
    private CuentaEliminadaEmailListener listener;

    @Test
    @DisplayName("enviarEmailDeConfirmacion_RenderizaYEnviaAlEmailReal")
    void enviarEmailDeConfirmacion_RenderizaYEnviaAlEmailReal() {
        when(emailRenderer.render(eq("cuenta-eliminada"), eq(Map.of("nombre", "Juan")))).thenReturn("<html>baja</html>");

        listener.enviarEmailDeConfirmacion(new CuentaEliminadaEvent("juan@test.com", "Juan"));

        verify(emailService).enviar("juan@test.com", "Tu cuenta fue eliminada", "<html>baja</html>");
    }
}
