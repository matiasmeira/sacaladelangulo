package com.matiasmeira.sacaladelangulo.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.auth.service.VerificacionEmailSolicitadaEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cubre el camino completo del registro de jugadores en 2 pasos tal como lo recorre un
 * usuario real: generar el token (POST /registro/iniciar), extraer el token crudo del
 * link publicado en VerificacionEmailSolicitadaEvent (igual que haría el link del mail),
 * validarlo contra el endpoint (GET /registro/verificar) y completar el registro (POST
 * /registro/completar). Nace del bug reportado de "el link de verificación no funciona"
 * (causa real: app.frontend-url desalineada con el puerto de Next.js, no un problema del
 * hasheo/expiración de RegistroVerificacionService, que este test confirma end-to-end).
 *
 * Se usa ApplicationEvents (no el envío real de mail) para capturar el link: el evento se
 * publica de forma síncrona en iniciarRegistro, antes de que el listener @Async /
 * @TransactionalEventListener(AFTER_COMMIT) decida cuándo y cómo mandar el mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-registro-verificacion;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false",
        "app.frontend-url=http://localhost:3000"
})
@DisplayName("Registro de jugadores en 2 pasos - flujo completo vía HTTP (link de verificación)")
class RegistroVerificacionFlujoCompletoIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEvents events;

    @Test
    @DisplayName("generarLink_ExtraerToken_VerificarYCompletar_DejaUsuarioConEmailVerificado")
    void generarLink_ExtraerToken_VerificarYCompletar_DejaUsuarioConEmailVerificado() throws Exception {
        String email = "jugador-e2e@test.com";

        mockMvc.perform(post("/api/v1/auth/registro/iniciar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk());

        String link = ultimoLinkPublicado();
        assertTrue(link.startsWith("http://localhost:3000/verificar?token="),
                "El link debe salir de app.frontend-url (fijada en @TestPropertySource) tal cual, sin reescrituras del backend");
        String token = extraerToken(link);

        mockMvc.perform(get("/api/v1/auth/registro/verificar").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.verificado").value(true));

        String bodyCompletar = objectMapper.writeValueAsString(Map.of(
                "token", token,
                "nombre", "Juan",
                "telefono", "1122334455",
                "password", "Password123"
        ));

        mockMvc.perform(post("/api/v1/auth/registro/completar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyCompletar))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty());

        Usuario usuario = usuarioRepository.findByEmail(email).orElseThrow();
        assertTrue(usuario.getEmailVerified());
    }

    @Test
    @DisplayName("verificarToken_TokenAdulterado_Rechaza400ConTokenInvalidoYNoCreaUsuario")
    void verificarToken_TokenAdulterado_Rechaza400ConTokenInvalidoYNoCreaUsuario() throws Exception {
        String email = "jugador-adulterado-e2e@test.com";

        mockMvc.perform(post("/api/v1/auth/registro/iniciar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isOk());

        String tokenReal = extraerToken(ultimoLinkPublicado());
        String tokenAdulterado = tokenReal.substring(0, tokenReal.length() - 1)
                + (tokenReal.endsWith("0") ? "1" : "0");

        mockMvc.perform(get("/api/v1/auth/registro/verificar").param("token", tokenAdulterado))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El token de verificación no es válido"));

        assertTrue(usuarioRepository.findByEmail(email).isEmpty(),
                "Un token adulterado no debe permitir crear la cuenta");
    }

    private String ultimoLinkPublicado() {
        return events.stream(VerificacionEmailSolicitadaEvent.class)
                .reduce((primero, ultimo) -> ultimo)
                .orElseThrow(() -> new AssertionError("No se publicó VerificacionEmailSolicitadaEvent"))
                .linkVerificacion();
    }

    private String extraerToken(String link) {
        String marcador = "token=";
        return link.substring(link.indexOf(marcador) + marcador.length());
    }
}
