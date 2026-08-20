package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado")
    void existsBySlug_DevuelveTrueSoloParaUnSlugYaAsignado() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno2@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Uno")
                .direccion("Calle Uno")
                .slug("complejo-uno")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.flush();

        assertTrue(establecimientoRepository.existsBySlug("complejo-uno"));
        assertFalse(establecimientoRepository.existsBySlug("complejo-dos"));
    }

    @Test
    @DisplayName("findBySlugAndIsActiveTrue_NoDevuelveComplejosInactivos")
    void findBySlugAndIsActiveTrue_NoDevuelveComplejosInactivos() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno3@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Inactivo")
                .direccion("Calle Dos")
                .slug("complejo-inactivo")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(false)
                .dueno(dueno)
                .build());
        entityManager.flush();

        assertTrue(establecimientoRepository.findBySlugAndIsActiveTrue("complejo-inactivo").isEmpty());
        assertTrue(establecimientoRepository.findBySlugAndIsActiveTrue("no-existe").isEmpty());
    }

    @Test
    @DisplayName("findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas")
    void findActivosPorDeporte_FiltraPorDeporteDeCanchasActivas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno4@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        Establecimiento conPadel = entityManager.persist(Establecimiento.builder()
                .nombre("Con Padel")
                .direccion("Calle Tres")
                .slug("con-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(java.util.Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(java.math.BigDecimal.valueOf(1000))
                .montoSena(java.math.BigDecimal.valueOf(200))
                .establecimiento(conPadel)
                .build());
        entityManager.persist(Establecimiento.builder()
                .nombre("Sin Padel")
                .direccion("Calle Cuatro")
                .slug("sin-padel")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository
                .findActivosPorDeporte(Deporte.PADEL, org.springframework.data.domain.Pageable.unpaged());

        assertEquals(1, resultado.size());
        assertEquals(conPadel.getId(), resultado.get(0).getId());
        assertEquals(2, establecimientoRepository
                .findActivosPorDeporte(null, org.springframework.data.domain.Pageable.unpaged()).size());
    }

    @Test
    @DisplayName("findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas")
    void findActivosPorDeporte_ConPageable_AcotaLaCantidadDeFilasDevueltas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-cap@test.com")
                .password("hash")
                .nombre("Carlos")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        for (int i = 0; i < 3; i++) {
            entityManager.persist(Establecimiento.builder()
                    .nombre("Complejo Cap " + i)
                    .direccion("Calle " + i)
                    .slug("complejo-cap-" + i)
                    .latitud(-34.6)
                    .longitud(-58.4)
                    .requiereSena(false)
                    .isActive(true)
                    .dueno(dueno)
                    .build());
        }
        entityManager.flush();

        List<Establecimiento> resultado = establecimientoRepository
                .findActivosPorDeporte(null, org.springframework.data.domain.PageRequest.of(0, 2));

        assertEquals(2, resultado.size());
    }
}
