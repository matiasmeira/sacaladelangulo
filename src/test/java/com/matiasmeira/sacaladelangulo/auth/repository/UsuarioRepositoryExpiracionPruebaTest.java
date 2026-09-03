package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UsuarioRepository - Finder de expiración de prueba (degradación TRIAL -> FREE)")
class UsuarioRepositoryExpiracionPruebaTest {

    private static final LocalDateTime AHORA = LocalDateTime.now();

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialVencido_LoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialVencido_LoDevuelve() {
        Usuario vencido = entityManager.persist(usuarioDePrueba(
                "vencido@test.com", PlanSuscripcion.TRIAL, AHORA.minusDays(1), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(1, resultado.getTotalElements());
        assertEquals(vencido.getId(), resultado.getContent().get(0).getId());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialNoVencido_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_TrialNoVencido_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("no-vencido@test.com", PlanSuscripcion.TRIAL, AHORA.plusDays(5), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_YaEnFreeOPremium_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_YaEnFreeOPremium_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("ya-free@test.com", PlanSuscripcion.FREE, AHORA.minusDays(1), null));
        entityManager.persist(usuarioDePrueba("ya-premium@test.com", PlanSuscripcion.PREMIUM, AHORA.minusDays(1), null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_CuentaEliminada_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_CuentaEliminada_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba(
                "eliminado@test.com", PlanSuscripcion.TRIAL, AHORA.minusDays(1), AHORA.minusHours(1)));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    @Test
    @DisplayName("findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_FechaFinPruebaNula_NoLoDevuelve")
    void findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull_FechaFinPruebaNula_NoLoDevuelve() {
        entityManager.persist(usuarioDePrueba("sin-fecha@test.com", PlanSuscripcion.TRIAL, null, null));
        entityManager.flush();

        Page<Usuario> resultado = usuarioRepository.findByPlanSuscripcionAndFechaFinPruebaBeforeAndDeletedAtIsNull(
                PlanSuscripcion.TRIAL, AHORA, Pageable.ofSize(50));

        assertEquals(0, resultado.getTotalElements());
    }

    private Usuario usuarioDePrueba(String email, PlanSuscripcion plan, LocalDateTime fechaFinPrueba, LocalDateTime deletedAt) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario de prueba")
                .rol(Role.OWNER)
                .planSuscripcion(plan)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(fechaFinPrueba)
                .deletedAt(deletedAt)
                .build();
    }
}
