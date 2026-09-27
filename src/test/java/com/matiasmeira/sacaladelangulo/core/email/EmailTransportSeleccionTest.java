package com.matiasmeira.sacaladelangulo.core.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica, sin levantar la app entera, qué EmailTransport queda activo según
 * resend.enabled/resend.api-key y el perfil activo.
 *
 * <p>Usa ConfigDataApplicationContextInitializer para que el runner cargue los
 * application*.properties REALES del classpath (incluido application-prod.properties) con
 * el perfil que se active en cada test — así estos tests protegen de verdad el archivo:
 * borrar resend.enabled=true de application-prod.properties rompe
 * perfilProd_SinConfigurarNada_FallaPorResendApiKey (verificado manualmente comentando esa
 * línea y corriendo la clase).
 *
 * <p>El contexto sólo registra LogEmailService/ResendEmailService (no DataSource, JWT ni
 * ImageKit): aunque ConfigData carga TODAS las properties de prod, ningún otro
 * placeholder sin default (imagekit.private-key, app.frontend-url, db.password, etc.) se
 * evalúa nunca, porque ningún bean de este contexto lo pide vía @Value — así que en el
 * escenario "prod sin configurar nada" el único candidato a tumbar el arranque es
 * resend.api-key.
 *
 * <p>La máquina que corre esta clase (shell, IDE) puede tener RESEND_API_KEY/RESEND_ENABLED
 * reales en el entorno. El primer initializer los saca del Environment ANTES de que corra
 * ConfigDataApplicationContextInitializer, así ninguna resolución de placeholder ni el
 * matching relajado de resend.enabled llega a verlos. Confirmado que hacía falta: con
 * RESEND_API_KEY/RESEND_ENABLED=true exportados en la shell, 3 de los 4 tests de esta clase
 * rompían sin este initializer. Los overrides que sí queremos (withPropertyValues) no se ven
 * afectados: ApplicationContextRunner los aplica como system properties, una fuente distinta
 * de systemEnvironment.
 *
 * <p>No neutraliza el "spring.config.import=optional:dotenv:" de application.properties:
 * comprobado (con un .env real con RESEND_API_KEY/RESEND_ENABLED=true en el working
 * directory durante toda esta sesión) que hoy no resuelve nada — no hay ninguna dependencia
 * en el classpath que registre un ConfigDataLocationResolver para el scheme "dotenv:". Si
 * eso cambia en el futuro, esta clase necesitaría un ajuste adicional.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Selección de EmailTransport según resend.enabled y perfil (carga application*.properties reales)")
class EmailTransportSeleccionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(EmailTransportSeleccionTest::aislarSystemEnvironmentReal)
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PlaceholderConfig.class, LogEmailService.class, ResendEmailService.class);

    private static void aislarSystemEnvironmentReal(ConfigurableApplicationContext context) {
        MutablePropertySources propertySources = context.getEnvironment().getPropertySources();
        if (propertySources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            propertySources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Collections.emptyMap()));
        }
    }

    @Test
    @DisplayName("perfilProd_SinConfigurarNada_FallaPorResendApiKey")
    void perfilProd_SinConfigurarNada_FallaPorResendApiKey() {
        // Nada simulado: ni RESEND_API_KEY ni RESEND_ENABLED. resend.enabled=true sale del
        // application-prod.properties real.
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("resend.api-key");
                });
    }

    @Test
    @DisplayName("perfilProd_ApiKeyVacia_FallaConElGuardDeResendEmailService")
    void perfilProd_ApiKeyVacia_FallaConElGuardDeResendEmailService() {
        // Simula sólo RESEND_API_KEY="" (la env var que referencia application-prod.properties
        // en resend.api-key=${RESEND_API_KEY}); resend.enabled=true sigue saliendo del archivo real.
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "RESEND_API_KEY=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("RESEND_API_KEY")
                            .hasMessageContaining("RESEND_ENABLED");
                });
    }

    @Test
    @DisplayName("sinConfigurarNadaYSinPerfilProd_QuedaLogEmailServiceConLogInfo")
    void sinConfigurarNadaYSinPerfilProd_QuedaLogEmailServiceConLogInfo(CapturedOutput output) {
        // Sin spring.profiles.active: ConfigData sólo carga la base (application.properties),
        // que trae resend.enabled=false por default — el mismo comportamiento de siempre.
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LogEmailService.class);
            assertThat(context).doesNotHaveBean(ResendEmailService.class);
        });

        assertThat(output).contains("LogEmailService activo");
        assertThat(output).doesNotContain("resend.enabled=false con perfil prod");
    }

    @Test
    @DisplayName("perfilProd_ResendEnabledFalseExplicito_QuedaLogEmailServiceConWarnVisible")
    void perfilProd_ResendEnabledFalseExplicito_QuedaLogEmailServiceConWarnVisible(CapturedOutput output) {
        // resend.enabled=true sale del application-prod.properties real; acá se simula el
        // override poniendo la property ya resuelta en vez de la env var RESEND_ENABLED: un
        // MapPropertySource de test no hace el matching relajado env-var -> property (eso es
        // exclusivo de SystemEnvironmentPropertySource) que sí verificamos con un env var de
        // verdad en la sesión anterior. Lo que este test cubre es la otra mitad: que un
        // override de mayor precedencia sigue ganándole al archivo de prod real.
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "resend.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LogEmailService.class);
                });

        assertThat(output).contains("resend.enabled=false con perfil prod");
    }

    @Configuration
    static class PlaceholderConfig {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
