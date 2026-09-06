package com.matiasmeira.sacaladelangulo.reserva.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TurnoFijoService le llama métodos package-private a ReservaService (validarFechas,
 * validarHorarioAtencion, etc., ver ReservaService) por inyección. En producción el
 * bean de ReservaService no es la instancia original: la clase lleva @Transactional,
 * así que Spring lo envuelve en un proxy CGLIB (subclase generada en runtime en el
 * mismo paquete, condición necesaria para poder overridear un método package-private).
 * {@link TurnoFijoServiceTest} no ejercita esa ruta real: instancia ReservaService con
 * `new`, sin pasar por el contenedor, así que nunca invoca el método a través del proxy.
 *
 * <p>Este test levanta el contexto de Spring, autocablea el bean real (proxiado) de
 * ReservaService, y llama directamente a uno de sus métodos package-private con
 * argumentos inválidos. Si la delegación del proxy hacia la lógica real no
 * funcionara, la excepción de negocio esperada no llegaría (o llegaría una excepción
 * distinta, de proxying). No escribe nada en la base: validarFechas no toca ningún
 * repositorio, así que alcanza con levantar el contexto contra H2 con Flyway apagado
 * (mismo patrón que RutasProtegidasCoincidenConControllersTest).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-turnofijo-proxy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        // Ver comentario equivalente en SacaladelanguloApplicationTests: evita que
        // FlywayAutoConfiguration corra las migraciones de Postgres contra este H2.
        "spring.flyway.enabled=false"
})
@DisplayName("ReservaService: TurnoFijoService le llama métodos package-private a través del proxy real de Spring")
class ReservaServiceDelegacionATravesDelProxyTest {

    @Autowired
    private ReservaService reservaService;

    @Test
    @DisplayName("validarFechas_LlamadaSobreElBeanProxiadoDeSpring_DelegaYLanzaLaExcepcionDeNegocio")
    void validarFechas_LlamadaSobreElBeanProxiadoDeSpring_DelegaYLanzaLaExcepcionDeNegocio() {
        // reservaService acá es el bean real que administra Spring (un proxy CGLIB, no
        // "new ReservaService(...)"): exactamente lo que TurnoFijoService tiene inyectado.
        LocalDateTime inicio = LocalDateTime.now().plusDays(1);
        LocalDateTime finAntesDelInicio = inicio.minusHours(1);

        IllegalArgumentException excepcion = assertThrows(IllegalArgumentException.class,
                () -> reservaService.validarFechas(inicio, finAntesDelInicio));

        assertTrue(excepcion.getMessage().contains("anterior a la de fin"),
                "la delegación del proxy tiene que llegar hasta la lógica real de validarFechas: "
                        + excepcion.getMessage());
    }
}
