package com.matiasmeira.sacaladelangulo.auth.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) que los finders de umbral de fin de prueba (ver
 * AvisoFinPruebaService, Fase 5) realmente acoten por el rango de fecha Y excluyan a los
 * usuarios que ya tienen el flag del umbral en true, mismo patrón que
 * EstablecimientoRepositoryTest.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("UsuarioRepository - Finders de aviso de fin de prueba (ver Fase 5)")
class UsuarioRepositoryFinPruebaTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Test
    @DisplayName("findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull_SoloDevuelveElUsuarioDentroDelRangoYSinAvisoEnviado")
    void findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull_SoloDevuelveElUsuarioDentroDelRangoYSinAvisoEnviado() {
        LocalDateTime desde = LocalDateTime.now().plusDays(7).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime hasta = desde.plusDays(1);

        Usuario dentroDelRangoSinAviso = entityManager.persist(usuarioDePrueba(
                "dentro-sin-aviso@test.com", desde.plusHours(2), false));

        entityManager.persist(usuarioDePrueba(
                "dentro-con-aviso@test.com", desde.plusHours(3), true));

        entityManager.persist(usuarioDePrueba(
                "fuera-de-rango@test.com", desde.plusDays(5), false));

        entityManager.flush();

        List<Usuario> resultado = usuarioRepository
                .findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(desde, hasta);

        assertEquals(1, resultado.size());
        assertEquals(dentroDelRangoSinAviso.getId(), resultado.get(0).getId());
    }

    @Test
    @DisplayName("findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull_ExcluyeAlDuenoConCuentaEliminada")
    void findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull_ExcluyeAlDuenoConCuentaEliminada() {
        LocalDateTime desde = LocalDateTime.now().plusDays(7).withHour(0).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime hasta = desde.plusDays(1);

        Usuario duenoEliminado = usuarioDePrueba("dueno-eliminado@test.com", desde.plusHours(2), false);
        duenoEliminado.setDeletedAt(LocalDateTime.now());
        entityManager.persist(duenoEliminado);

        entityManager.flush();

        List<Usuario> resultado = usuarioRepository
                .findByFechaFinPruebaBetweenAndAvisoFinPrueba7EnviadoFalseAndDeletedAtIsNull(desde, hasta);

        assertEquals(0, resultado.size());
    }

    private Usuario usuarioDePrueba(String email, LocalDateTime fechaFinPrueba, boolean aviso7Enviado) {
        return Usuario.builder()
                .email(email)
                .password("hash")
                .nombre("Usuario de prueba")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .fechaFinPrueba(fechaFinPrueba)
                .avisoFinPrueba7Enviado(aviso7Enviado)
                .avisoFinPrueba3Enviado(false)
                .avisoFinPrueba1Enviado(false)
                .build();
    }
}
