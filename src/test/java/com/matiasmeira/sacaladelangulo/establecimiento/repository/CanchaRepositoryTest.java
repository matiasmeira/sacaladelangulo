package com.matiasmeira.sacaladelangulo.establecimiento.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Tarifa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("CanchaRepository - Fetch en lote con deportes y tarifas")
class CanchaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CanchaRepository canchaRepository;

    @Test
    @DisplayName("findActivasConDeportesYTarifasByEstablecimientoIdIn_TraeDeportesYTarifasSinLazyException")
    void findActivasConDeportesYTarifasByEstablecimientoIdIn_TraeDeportesYTarifasSinLazyException() {
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
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .establecimiento(establecimiento)
                .build());
        entityManager.persist(Tarifa.builder()
                .cancha(cancha)
                .diaSemana(DayOfWeek.MONDAY)
                .horaInicio(LocalTime.of(9, 0))
                .horaFin(LocalTime.of(23, 0))
                .precio(BigDecimal.valueOf(6000))
                .build());
        entityManager.flush();
        entityManager.clear();

        List<Cancha> resultado = canchaRepository
                .findActivasConDeportesYTarifasByEstablecimientoIdIn(List.of(establecimiento.getId()));

        assertEquals(1, resultado.size());
        assertTrue(resultado.get(0).getDeportes().contains(Deporte.FUTBOL));
        assertEquals(1, resultado.get(0).getTarifas().size());
        assertEquals(0, BigDecimal.valueOf(6000).compareTo(resultado.get(0).getTarifas().get(0).getPrecio()));
    }
}
