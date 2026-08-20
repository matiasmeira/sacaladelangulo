package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.BloqueoCancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("BloqueoCanchaRepository - Consulta en lote por rango, a través de varios establecimientos")
class BloqueoCanchaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BloqueoCanchaRepository bloqueoCanchaRepository;

    @Test
    @DisplayName("findByEstablecimientoIdInAndRango_DevuelveSoloLosBloqueosQueSeSuperponen")
    void findByEstablecimientoIdInAndRango_DevuelveSoloLosBloqueosQueSeSuperponen() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Test")
                .direccion("Calle Test")
                .slug("complejo-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        BloqueoCancha superpuesto = entityManager.persist(BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 9, 0))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 11, 0))
                .motivo("Mantenimiento")
                .build());
        entityManager.persist(BloqueoCancha.builder()
                .cancha(cancha)
                .fechaInicio(LocalDateTime.of(2026, 8, 10, 15, 0))
                .fechaFin(LocalDateTime.of(2026, 8, 10, 16, 0))
                .motivo("No se superpone")
                .build());
        entityManager.flush();

        List<BloqueoCancha> resultado = bloqueoCanchaRepository.findByEstablecimientoIdInAndRango(
                List.of(establecimiento.getId()),
                LocalDateTime.of(2026, 8, 10, 10, 0),
                LocalDateTime.of(2026, 8, 10, 11, 0));

        assertEquals(1, resultado.size());
        assertEquals(superpuesto.getId(), resultado.get(0).getId());
    }
}
