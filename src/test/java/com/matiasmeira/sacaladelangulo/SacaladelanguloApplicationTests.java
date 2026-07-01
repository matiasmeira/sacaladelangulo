package com.matiasmeira.sacaladelangulo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
    "spring.config.import=" // Anula la carga del .env para evitar colisiones
})
class SacaladelanguloApplicationTests {

	@Test
	void contextLoads() {
	}

}
