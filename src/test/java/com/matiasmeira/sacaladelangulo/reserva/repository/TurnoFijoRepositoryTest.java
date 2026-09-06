package com.matiasmeira.sacaladelangulo.reserva.repository;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoTurnoFijo;
import com.matiasmeira.sacaladelangulo.reserva.model.TurnoFijo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("TurnoFijoRepository")
class TurnoFijoRepositoryTest {

    @Autowired private TurnoFijoRepository turnoFijoRepository;
    @Autowired private EntityManager em;

    /** Deja persistido un establecimiento con una cancha y devuelve la cancha. */
    private Cancha canchaPersistida() {
        Usuario dueno = Usuario.builder()
                .nombre("Dueno").email("dueno@test.com").password("x")
                .rol(Role.OWNER).isActive(true).tokenVersion(0)
                .build();
        em.persist(dueno);

        Establecimiento est = Establecimiento.builder()
                .nombre("Complejo").direccion("Calle Falsa 123").slug("complejo")
                .latitud(-34.6).longitud(-58.4).requiereSena(true)
                .dueno(dueno).isActive(true)
                .build();
        em.persist(est);

        Cancha cancha = Cancha.builder()
                .nombre("Cancha 1").establecimiento(est).isActive(true)
                .deportes(Set.of(Deporte.FUTBOL_5))
                .precioBase(BigDecimal.valueOf(10000))
                .montoSena(BigDecimal.valueOf(3000))
                .build();
        em.persist(cancha);
        em.flush();
        return cancha;
    }

    @Test
    @DisplayName("findByCancha_Establecimiento_IdAndEstado_TraeSoloLosDelEstadoPedido")
    void findByEstablecimientoYEstado_TraeSoloLosDelEstadoPedido() {
        Cancha cancha = canchaPersistida();
        Long estId = cancha.getEstablecimiento().getId();

        turnoFijoRepository.save(turnoFijo(cancha, EstadoTurnoFijo.ACTIVO, null));
        turnoFijoRepository.save(turnoFijo(cancha, EstadoTurnoFijo.CANCELADO, LocalDate.of(2026, 10, 1)));

        var activos = turnoFijoRepository.findByCancha_Establecimiento_IdAndEstado(
                estId, EstadoTurnoFijo.ACTIVO, PageRequest.of(0, 10));

        assertThat(activos.getContent()).hasSize(1);
        assertThat(activos.getContent().get(0).getEstado()).isEqualTo(EstadoTurnoFijo.ACTIVO);
    }

    private TurnoFijo turnoFijo(Cancha cancha, EstadoTurnoFijo estado, LocalDate canceladoDesde) {
        return TurnoFijo.builder()
                .cancha(cancha)
                .deporteSeleccionado(Deporte.FUTBOL_5)
                .diaSemana(DayOfWeek.TUESDAY)
                .horaInicio(LocalTime.of(20, 0))
                .horaFin(LocalTime.of(21, 0))
                .fechaInicioPeriodo(LocalDate.of(2026, 9, 1))
                .fechaFinPeriodo(LocalDate.of(2026, 12, 31))
                .nombreClienteManual("Grupo del Colo")
                .estado(estado)
                .canceladoDesde(canceladoDesde)
                .build();
    }
}
