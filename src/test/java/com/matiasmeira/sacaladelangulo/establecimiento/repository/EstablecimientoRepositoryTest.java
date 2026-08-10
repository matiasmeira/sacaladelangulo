package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) que findCercanosYPorDeporte siga siendo JPQL válido
 * después de agregarle el pre-filtro de bounding box (ver M29 en la auditoría): un test
 * puramente mockeado no detecta un error de sintaxis en funciones nativas (RADIANS/COS/
 * ACOS) que solo se resuelven contra un motor de base real.
 */
@DataJpaTest
// ddl-auto=validate es el default de la config base (ver A10): la base embebida que usa
// @DataJpaTest arranca vacía, así que acá hace falta create-drop para generar el esquema.
// spring.flyway.enabled=false evita que FlywayAutoConfiguration corra las migraciones de
// Postgres (ver db/migration/V1__baseline.sql) contra este H2 en memoria.
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("EstablecimientoRepository - Búsqueda geográfica (ver M29)")
class EstablecimientoRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    @Test
    @DisplayName("findCercanosYPorDeporte_DevuelveSoloElEstablecimientoDentroDelRadio")
    void findCercanosYPorDeporte_DevuelveSoloElEstablecimientoDentroDelRadio() {
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

        // Obelisco, CABA
        Establecimiento cercano = entityManager.persist(Establecimiento.builder()
                .nombre("Cercano")
                .direccion("Cerca")
                .slug("cercano")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());

        // Ushuaia, a ~2500km de CABA: bien fuera del radio de búsqueda
        entityManager.persist(Establecimiento.builder()
                .nombre("Lejano")
                .direccion("Lejos")
                .slug("lejano")
                .latitud(-54.8019)
                .longitud(-68.3030)
                .requiereSena(true)
                .isActive(true)
                .dueno(dueno)
                .build());

        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository.findCercanosYPorDeporte(
                -34.6037, -58.3816, 10.0, null);

        assertEquals(1, resultado.size());
        assertEquals(cercano.getId(), resultado.get(0).getId());
    }
}
