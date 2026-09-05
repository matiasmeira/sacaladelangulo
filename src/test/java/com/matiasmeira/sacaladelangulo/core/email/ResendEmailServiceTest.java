package com.matiasmeira.sacaladelangulo.core.email;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResendEmailService - Tests de envío vía Resend")
class ResendEmailServiceTest {

    @Mock
    private Resend resendClient;

    @Mock
    private Emails emails;

    private ResendEmailService resendEmailService;

    @BeforeEach
    void setUp() {
        resendEmailService = new ResendEmailService(resendClient, "Canchear <onboarding@resend.dev>");
    }

    @Test
    @DisplayName("enviar_Exito_LlamaAlClienteDeResend")
    void enviar_Exito_LlamaAlClienteDeResend() throws ResendException {
        // Arrange
        when(resendClient.emails()).thenReturn(emails);
        when(emails.send(any(CreateEmailOptions.class))).thenReturn(new CreateEmailResponse());

        // Act & Assert
        assertDoesNotThrow(() -> resendEmailService.enviar("jugador@test.com", "Verificá tu cuenta", "<p>Hola</p>"));
        verify(emails).send(any(CreateEmailOptions.class));
    }

    @Test
    @DisplayName("enviar_Fallo_PropagaComoRuntimeExceptionParaElAsyncUncaughtHandler")
    void enviar_Fallo_PropagaComoRuntimeExceptionParaElAsyncUncaughtHandler() throws ResendException {
        // Arrange: simula una falla de la API de Resend (ej. credenciales inválidas, rate limit)
        when(resendClient.emails()).thenReturn(emails);
        when(emails.send(any(CreateEmailOptions.class))).thenThrow(new ResendException("Falla simulada de Resend", null));

        // Act & Assert: no debe perderse silenciosamente; el listener @Async la deja
        // subir para que la capture el AsyncUncaughtExceptionHandler de AsyncConfig.
        assertThrows(RuntimeException.class,
                () -> resendEmailService.enviar("jugador@test.com", "Verificá tu cuenta", "<p>Hola</p>"));
    }
}
