package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.mails.dto.EnviarOfertaRequest;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/admin/mails/oferta. Sin @PreAuthorize a propósito (ver pendiente 44): el rol
 * ADMIN se valida en OfertaMarketingService.enviarOferta (línea 29 del service), que lanza
 * AccessDeniedException, traducida a 403 por GlobalExceptionHandler (línea 87).
 */
@DisplayName("POST /api/v1/admin/mails/oferta")
class AdminMailsOfertaAutorizacionTest extends AbstractSecurityWebTest {

    private static final String URL = "/api/v1/admin/mails/oferta";
    private static final String CUERPO = "{\"asunto\":\"Oferta\",\"cuerpoHtml\":\"<p>Hola</p>\"}";
    private static final String MENSAJE_403 = "No está autorizado para enviar ofertas de marketing";

    private ResultActions enviar(String authorization) throws Exception {
        var req = post(URL).contentType(MediaType.APPLICATION_JSON).content(CUERPO);
        if (authorization != null) {
            req = req.header("Authorization", authorization);
        }
        return mockMvc.perform(req);
    }

    @Test
    @DisplayName("sinToken_Devuelve401")
    void sinToken_Devuelve401() throws Exception {
        enviar(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autenticado"));
        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    @Test
    @DisplayName("jugador_Devuelve403YNoEnvia")
    void jugador_Devuelve403YNoEnvia() throws Exception {
        enviar(bearer(jugador))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403));
        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    @Test
    @DisplayName("dueno_Devuelve403YNoEnvia")
    void dueno_Devuelve403YNoEnvia() throws Exception {
        enviar(bearer(duenoA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403));
        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    @Test
    @DisplayName("empleado_Devuelve403YNoEnvia")
    void empleado_Devuelve403YNoEnvia() throws Exception {
        enviar(bearer(empleadoConPermiso))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403));
        verifyNoInteractions(ofertaMarketingBatchSender);
    }

    @Test
    @DisplayName("admin_Devuelve202YDisparaElEnvio")
    void admin_Devuelve202YDisparaElEnvio() throws Exception {
        enviar(bearer(admin)).andExpect(status().isAccepted());
        verify(ofertaMarketingBatchSender).enviarEnLotes(any(EnviarOfertaRequest.class));
    }
}
