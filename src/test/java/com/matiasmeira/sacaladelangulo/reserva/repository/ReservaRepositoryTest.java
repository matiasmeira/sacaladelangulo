package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) que countReservasFuturasActivas cuente solo lo que
 * PoliticaCancelacionService necesita informar: CONFIRMADA/PENDIENTE_SENA con
 * fechaHoraInicio futura. Un test con el repositorio mockeado no ejercitaría el filtro de
 * estado ni el de fecha del JPQL.
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("ReservaRepository - countReservasFuturasActivas")
class ReservaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ReservaRepository reservaRepository;

    @Test
    @DisplayName("countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas")
    void countReservasFuturasActivas_CuentaSoloConfirmadaYPendienteSenaFuturas() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-politica@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Politica")
                .direccion("Calle Politica 123")
                .slug("complejo-politica")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());

        Cancha cancha = entityManager.persist(Cancha.builder()
                .nombre("Cancha 1")
                .deportes(Set.of(Deporte.PADEL))
                .isActive(true)
                .precioBase(BigDecimal.valueOf(1000))
                .montoSena(BigDecimal.valueOf(200))
                .establecimiento(establecimiento)
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.plusDays(2)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(3)));
        // No deben contarse: cancelada futura y confirmada ya pasada.
        entityManager.persist(reservaDe(cancha, EstadoReserva.CANCELADA, ahora.plusDays(1)));
        entityManager.persist(reservaDe(cancha, EstadoReserva.CONFIRMADA, ahora.minusDays(1)));
        entityManager.flush();

        long resultado = reservaRepository.countReservasFuturasActivas(establecimiento.getId(), ahora);

        assertEquals(2, resultado);
    }

    private Reserva reservaDe(Cancha cancha, EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .build();
    }
}
