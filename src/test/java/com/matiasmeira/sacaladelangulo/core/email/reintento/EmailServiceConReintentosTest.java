package com.matiasmeira.sacaladelangulo.core.email.reintento;

import com.matiasmeira.sacaladelangulo.core.email.EmailTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailServiceConReintentos - politica ante fallo del proveedor")
class EmailServiceConReintentosTest {

    private static final String DESTINATARIO = "jugador@saque.test";
    private static final String ASUNTO = "Verificá tu email";
    private static final String CUERPO = "<p>hola</p>";

    @Mock
    private EmailTransport emailTransport;

    @Mock
    private EmailPendienteRegistro emailPendienteRegistro;

    @InjectMocks
    private EmailServiceConReintentos emailService;

    @Test
    @DisplayName("envioExitoso_noEncolaYLimpiaPendientesPrevios")
    void envioExitoso_noEncolaYLimpiaPendientesPrevios() {
        emailService.enviar(DESTINATARIO, ASUNTO, CUERPO);

        verify(emailTransport).enviar(DESTINATARIO, ASUNTO, CUERPO);
        verify(emailPendienteRegistro, never()).encolar(anyString(), anyString(), anyString(), any());
        // Un envío que salió bien deja sin efecto lo que hubiera encolado antes: reintentarlo
        // mandaría contenido viejo (por ejemplo, un link cuyo token ya fue reemplazado).
        verify(emailPendienteRegistro).resolver(DESTINATARIO, ASUNTO);
    }

    /**
     * El corazón del cambio: antes esta excepción subía hasta el handler de @Async, se
     * logueaba y el email se perdía. Ahora queda encolado.
     */
    @Test
    @DisplayName("falloDelProveedor_encolaYNoPropagaLaExcepcion")
    void falloDelProveedor_encolaYNoPropagaLaExcepcion() {
        doThrow(new RuntimeException("Resend 503")).when(emailTransport).enviar(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> emailService.enviar(DESTINATARIO, ASUNTO, CUERPO));

        verify(emailPendienteRegistro).encolar(DESTINATARIO, ASUNTO, CUERPO, "Resend 503");
        // No debe "resolver" nada: no hay envío exitoso que supere al pendiente.
        verify(emailPendienteRegistro, never()).resolver(anyString(), anyString());
    }

    /**
     * OfertaMarketingBatchSender.enviarEnLotes no captura por destinatario: si la
     * excepción se propagara, un solo rebote abortaría el broadcast para todos los que
     * vinieran después. Que no propague es lo que lo mantiene con vida.
     */
    @Test
    @DisplayName("falloDeUnDestinatario_noCortaElEnvioALosSiguientes")
    void falloDeUnDestinatario_noCortaElEnvioALosSiguientes() {
        doThrow(new RuntimeException("direccion invalida"))
                .when(emailTransport).enviar(eq("rebota@saque.test"), anyString(), anyString());

        assertDoesNotThrow(() -> {
            emailService.enviar("primero@saque.test", ASUNTO, CUERPO);
            emailService.enviar("rebota@saque.test", ASUNTO, CUERPO);
            emailService.enviar("tercero@saque.test", ASUNTO, CUERPO);
        });

        verify(emailTransport).enviar(eq("tercero@saque.test"), anyString(), anyString());
    }
}
