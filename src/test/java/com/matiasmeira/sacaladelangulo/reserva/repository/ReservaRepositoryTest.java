package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Valida contra una base real (H2) consultas de ReservaRepository cuyo JPQL un test con el
 * repositorio mockeado no ejercitaría: countReservasFuturasActivas (que
 * PoliticaCancelacionService necesita filtrado a CONFIRMADA/PENDIENTE_SENA con
 * fechaHoraInicio futura) y agregadosPorTurnoFijo (el GROUP BY agregado que usa
 * TurnoFijoService.listar).
 */
@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("ReservaRepository - Consultas agregadas contra una base real")
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

    /**
     * Valida contra una base real que agregadosPorTurnoFijo (usada por
     * TurnoFijoService.listar para resolver cantidadOcurrenciasActivas/proximaOcurrencia de
     * toda una página en una sola consulta) cuente y agrupe bien: TurnoFijoServiceTest la
     * mockea, así que nunca ejercita el JPQL en sí (el GROUP BY, el filtro de estados y el
     * de fecha futura).
     */
    @Test
    @DisplayName("agregadosPorTurnoFijo_CuentaSoloOcurrenciasFuturasActivasYLasAgrupaPorSerie")
    void agregadosPorTurnoFijo_CuentaSoloOcurrenciasFuturasActivasYLasAgrupaPorSerie() {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-turnofijo@test.com")
                .password("hash")
                .nombre("Dueno")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());

        Establecimiento establecimiento = entityManager.persist(Establecimiento.builder()
                .nombre("Complejo Turno Fijo")
                .direccion("Calle Fija 456")
                .slug("complejo-turno-fijo")
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

        TurnoFijo conOcurrenciasFuturas = entityManager.persist(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Cliente Fijo")
                .build());

        TurnoFijo sinOcurrenciasFuturas = entityManager.persist(TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.PADEL)
                .diaSemana(DayOfWeek.WEDNESDAY)
                .horaInicio(LocalTime.of(19, 0))
                .horaFin(LocalTime.of(20, 0))
                .fechaInicioPeriodo(LocalDate.of(2030, 1, 1))
                .fechaFinPeriodo(LocalDate.of(2030, 12, 31))
                .estado(EstadoTurnoFijo.ACTIVO)
                .nombreClienteManual("Otro Cliente")
                .build());

        LocalDateTime ahora = LocalDateTime.now();

        // Cuentan: dos ocurrencias futuras vivas de conOcurrenciasFuturas.
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CONFIRMADA, ahora.plusDays(9)));
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.PENDIENTE_SENA, ahora.plusDays(2)));
        // No cuentan, del mismo turno fijo: cancelada futura y confirmada ya pasada.
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CANCELADA, ahora.plusDays(5)));
        entityManager.persist(reservaDeTurnoFijo(cancha, conOcurrenciasFuturas, EstadoReserva.CONFIRMADA, ahora.minusDays(3)));
        // No debe filtrarse hacia conOcurrenciasFuturas: es de otra serie, y encima cancelada.
        entityManager.persist(reservaDeTurnoFijo(cancha, sinOcurrenciasFuturas, EstadoReserva.CANCELADA, ahora.plusDays(1)));
        entityManager.flush();

        List<Object[]> filas = reservaRepository.agregadosPorTurnoFijo(
                List.of(conOcurrenciasFuturas.getId(), sinOcurrenciasFuturas.getId()), ahora);

        // sinOcurrenciasFuturas no tiene ninguna ocurrencia viva: no aparece ninguna fila
        // para esa serie (a diferencia de un LEFT JOIN, que traería 0/null).
        assertEquals(1, filas.size());
        Object[] fila = filas.get(0);
        assertEquals(conOcurrenciasFuturas.getId(), fila[0]);
        assertEquals(2L, fila[1]);
        // truncatedTo(MILLIS): H2 redondea a microsegundos al persistir, así que comparar el
        // LocalDateTime completo (con nanosegundos) es flaky por precisión, no por el JPQL.
        assertEquals(ahora.plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
                ((LocalDateTime) fila[2]).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    private Reserva reservaDeTurnoFijo(Cancha cancha, TurnoFijo turnoFijo, EstadoReserva estado, LocalDateTime fechaHoraInicio) {
        return Reserva.builder()
                .cancha(cancha)
                .turnoFijo(turnoFijo)
                .deporteSeleccionado(Deporte.PADEL)
                .fechaHoraInicio(fechaHoraInicio)
                .fechaHoraFin(fechaHoraInicio.plusHours(1))
                .estado(estado)
                .precioTotal(BigDecimal.valueOf(1000))
                .build();
    }
}
