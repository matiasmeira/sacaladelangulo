package com.matiasmeira.sacaladelangulo.e2e;

import com.matiasmeira.sacaladelangulo.SacaladelanguloApplication;
import org.springframework.boot.SpringApplication;

/**
 * Arranca la aplicación real con el perfil e2e (ver application-e2e.properties). Vive en
 * src/test: no entra al jar. Se ejecuta con spring-boot:test-run.
 */
public class E2eApplication {

    public static void main(String[] args) {
        SpringApplication.from(SacaladelanguloApplication::main)
                .withAdditionalProfiles("e2e")
                .run(args);
    }
}
