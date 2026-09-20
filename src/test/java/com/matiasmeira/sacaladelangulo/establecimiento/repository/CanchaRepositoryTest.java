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
                .deportes(Set.of(Deporte.FUTBOL_5))
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
        assertTrue(resultado.get(0).getDeportes().contains(Deporte.FUTBOL_5));
        assertEquals(1, resultado.get(0).getTarifas().size());
        assertEquals(0, BigDecimal.valueOf(6000).compareTo(resultado.get(0).getTarifas().get(0).getPrecio()));
    }

    @Test
    @DisplayName("findByEstablecimientoIdAndIsActiveTrue_conCanchaLogicaDeVariosDeportes_noDuplicaCanchasFisicas")
    void findByEstablecimientoIdAndIsActiveTrue_conCanchaLogicaDeVariosDeportes_noDuplicaCanchasFisicas() {
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
        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Test 2")
                .direccion("Calle Test 2")
                .slug("complejo-test-2")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        // El @EntityGraph de findByEstablecimientoIdAndIsActiveTrue trae "canchasFisicas" y
        // "deportes" en la misma consulta. Con canchasFisicas como bag (List), Hibernate 6
        // arma un unico JOIN de ambas colecciones y produce el producto cartesiano:
        // 3 fisicas x 2 deportes = 6 filas, cada fisica repetida.
        List<Cancha> fisicas = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            fisicas.add(entityManager.persist(Cancha.builder()
                    .nombre("Fisica " + i)
                    .deportes(Set.of(Deporte.FUTBOL_5))
                    .isActive(true)
                    .precioBase(BigDecimal.valueOf(5000))
                    .montoSena(BigDecimal.valueOf(1000))
                    .establecimiento(establecimiento)
                    .build()));
        }

        Cancha logica = Cancha.builder()
                .nombre("Cancha Logica (pool de 3)")
                .deportes(Set.of(Deporte.FUTBOL_5, Deporte.FUTBOL_7))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(15000))
                .montoSena(BigDecimal.valueOf(3000))
                .establecimiento(establecimiento)
                .canchasFisicas(new java.util.LinkedHashSet<>(fisicas))
                .build();
        entityManager.persist(logica);

        entityManager.flush();
        entityManager.clear();

        List<Cancha> resultado = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId());

        Cancha logicaLeida = resultado.stream()
                .filter(c -> c.getNombre().startsWith("Cancha Logica"))
                .findFirst()
                .orElseThrow();

        assertEquals(3, logicaLeida.getCanchasFisicas().size(),
                "El pool tiene 3 canchas fisicas; no debe duplicarse por tener 2 deportes asociados");
    }

    /**
     * findByEstablecimientoId (sin AndIsActiveTrue) es lo que usan PoolCanchaCalculator y
     * CanchaService.validarDesactivacion para no perder de vista una cancha LÓGICA
     * desactivada que todavía tiene reservas futuras vigentes (ver diagnóstico de
     * sobreventa por lógica desactivada): tiene que traer también las inactivas.
     */
    @Test
    @DisplayName("findByEstablecimientoId_TraeActivasEInactivas")
    void findByEstablecimientoId_TraeActivasEInactivas() {
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
        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Test 3")
                .direccion("Calle Test 3")
                .slug("complejo-test-3")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        entityManager.persist(Cancha.builder()
                .nombre("Cancha Activa")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .establecimiento(establecimiento)
                .build());
        entityManager.persist(Cancha.builder()
                .nombre("Cancha Inactiva")
                .deportes(Set.of(Deporte.FUTBOL_5))
                .isActive(false)
                .precioBase(BigDecimal.valueOf(5000))
                .montoSena(BigDecimal.valueOf(1000))
                .establecimiento(establecimiento)
                .build());
        entityManager.flush();
        entityManager.clear();

        List<Cancha> incluyendoInactivas = canchaRepository.findByEstablecimientoId(establecimiento.getId());
        List<Cancha> soloActivas = canchaRepository.findByEstablecimientoIdAndIsActiveTrue(establecimiento.getId());

        assertEquals(2, incluyendoInactivas.size());
        assertEquals(1, soloActivas.size());
        assertTrue(soloActivas.get(0).getIsActive());
    }
}
