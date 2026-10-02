package com.matiasmeira.sacaladelangulo.e2e;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Resetea la base e2e (clean + migrate) en cada arranque, con la guarda de GuardaBaseE2e. */
@Configuration
@Profile("e2e")
class E2eFlywayConfig {

    @Bean
    FlywayMigrationStrategy resetearBaseE2e(@Value("${spring.datasource.url}") String url) {
        return flyway -> {
            GuardaBaseE2e.verificar(url);
            flyway.clean();
            flyway.migrate();
        };
    }
}
