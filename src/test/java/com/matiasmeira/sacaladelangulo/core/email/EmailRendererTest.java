package com.matiasmeira.sacaladelangulo.core.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EmailRenderer - Motor de plantillas Thymeleaf")
class EmailRendererTest {

    @Test
    @DisplayName("render_PlantillaExistente_DevuelveHtmlConLaVariableInterpolada")
    void render_PlantillaExistente_DevuelveHtmlConLaVariableInterpolada() {
        EmailRenderer emailRenderer = new EmailRenderer();

        String html = emailRenderer.render("cuenta-eliminada", Map.of("nombre", "Juan"));

        assertTrue(html.contains("Juan"));
    }
}
