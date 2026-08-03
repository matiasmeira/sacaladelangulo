package com.matiasmeira.sacaladelangulo.core.email;

import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;

/**
 * Renderiza plantillas HTML de emails transaccionales (templates/email/*.html) usando un
 * TemplateEngine de Thymeleaf propio, configurado con su propio ClassLoaderTemplateResolver
 * apuntando a templates/email/ — deliberadamente aislado del resolver de vistas web
 * autoconfigurado por Spring Boot (que hoy no existe en este proyecto, que es solo API,
 * pero así este componente no queda acoplado a esa configuración si en el futuro se agrega
 * alguna vista web).
 */
@Component
public class EmailRenderer {

    private final TemplateEngine templateEngine;

    public EmailRenderer() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/email/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(true);

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setTemplateResolver(templateResolver);
    }

    /**
     * Renderiza la plantilla {@code nombrePlantilla} (sin extensión, relativa a
     * templates/email/) con las variables de {@code modelo} disponibles en su contexto.
     */
    public String render(String nombrePlantilla, Map<String, Object> modelo) {
        Context context = new Context();
        context.setVariables(modelo);
        return templateEngine.process(nombrePlantilla, context);
    }
}
