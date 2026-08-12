package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.DiaNoLaborable;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("DiaNoLaborableRepository - Consulta en lote por fecha puntual")
class DiaNoLaborableRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private DiaNoLaborableRepository diaNoLaborableRepository;

    private Establecimiento establecimiento(Usuario dueno, String slug) {
        return entityManager.persist(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle " + slug)
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    @Test
    @DisplayName("findByEstablecimientoIdInAndFecha_DevuelveSoloLosQueTienenEsaFechaMarcada")
    void findByEstablecimientoIdInAndFecha_DevuelveSoloLosQueTienenEsaFechaMarcada() {
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

        Establecimiento conFeriado = establecimiento(dueno, "con-feriado");
        Establecimiento sinFeriado = establecimiento(dueno, "sin-feriado");

        LocalDate fecha = LocalDate.of(2026, 12, 25);
        entityManager.persist(DiaNoLaborable.builder()
                .fecha(fecha)
                .motivo("Feriado")
                .establecimiento(conFeriado)
                .build());
        entityManager.flush();

        List<DiaNoLaborable> resultado = diaNoLaborableRepository
                .findByEstablecimientoIdInAndFecha(List.of(conFeriado.getId(), sinFeriado.getId()), fecha);

        assertEquals(1, resultado.size());
        assertEquals(conFeriado.getId(), resultado.get(0).getEstablecimiento().getId());
    }
}
