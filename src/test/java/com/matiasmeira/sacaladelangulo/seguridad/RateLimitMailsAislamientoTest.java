package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pendiente 55: el bucket de mails del RateLimitFilter (5 por minuto por IP cuando no hay sesión)
 * vive en el contexto compartido de AbstractSecurityWebTest. Si todos los requests salieran de la
 * misma IP, un test que agota el cupo dejaría en 429 a los siguientes. La base asigna una IP propia
 * a cada test, así que cada repetición dispone del cupo completo.
 */
@DisplayName("Rate limit de mails: cada test arranca con su propio cupo")
class RateLimitMailsAislamientoTest extends AbstractSecurityWebTest {

    private static final int CUPO_MAILS_POR_MINUTO = 5;

    @RepeatedTest(3)
    @DisplayName("sinToken_AgotarElCupoDeMailsNoAfectaALosOtrosTests")
    void sinToken_AgotarElCupoDeMailsNoAfectaALosOtrosTests() throws Exception {
        for (int i = 1; i <= CUPO_MAILS_POR_MINUTO; i++) {
            int status = mockMvc.perform(post("/api/v1/mails/baja").contentType("application/json").content("{}"))
                    .andReturn().getResponse().getStatus();
            assertNotEquals(429, status, "el request " + i + " de " + CUPO_MAILS_POR_MINUTO + " no puede dar 429");
        }
    }
}
