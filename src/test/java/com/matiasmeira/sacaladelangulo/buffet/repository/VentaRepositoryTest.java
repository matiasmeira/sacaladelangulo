package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("VentaRepository - buscarPaginado")
class VentaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private VentaRepository ventaRepository;

    private Establecimiento establecimiento;

    private Establecimiento persistirEstablecimiento(String slug) {
        Usuario dueno = entityManager.persist(Usuario.builder()
                .email("dueno-" + slug + "@test.com")
                .password("hash")
                .nombre("Dueño Test")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .build());
        return entityManager.persist(Establecimiento.builder()
                .nombre("Complejo " + slug)
                .direccion("Calle Test 123")
                .slug(slug)
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    private Venta persistirVenta(EstadoVenta estado, LocalDateTime fechaHora, BigDecimal total) {
        return entityManager.persist(Venta.builder()
                .establecimiento(establecimiento)
                .fechaHora(fechaHora)
                .total(total)
                .estado(estado)
                .metodoPago(MetodoPago.EFECTIVO)
                .build());
    }

    @Test
    @DisplayName("buscarPaginado_SinEstado_TraeConfirmadaYCanceladaDentroDelRangoExcluyeFueraDeRango")
    void buscarPaginado_SinEstado_TraeConfirmadaYCanceladaDentroDelRangoExcluyeFueraDeRango() {
        establecimiento = persistirEstablecimiento("sin-estado");
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        persistirVenta(EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 2, 1, 10, 0), BigDecimal.valueOf(3000));
        entityManager.flush();
        entityManager.clear();

        Page<Venta> page = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 20));

        assertEquals(2, page.getTotalElements());
        assertTrue(page.getContent().stream().anyMatch(v -> v.getEstado() == EstadoVenta.CONFIRMADA));
        assertTrue(page.getContent().stream().anyMatch(v -> v.getEstado() == EstadoVenta.CANCELADA));
    }

    @Test
    @DisplayName("buscarPaginado_ConEstado_FiltraSoloEseEstado")
    void buscarPaginado_ConEstado_FiltraSoloEseEstado() {
        establecimiento = persistirEstablecimiento("con-estado");
        persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, 5, 10, 0), BigDecimal.valueOf(1000));
        persistirVenta(EstadoVenta.CANCELADA, LocalDateTime.of(2026, 1, 10, 10, 0), BigDecimal.valueOf(2000));
        entityManager.flush();
        entityManager.clear();

        Page<Venta> page = ventaRepository.buscarPaginado(
                establecimiento.getId(), EstadoVenta.CANCELADA,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(EstadoVenta.CANCELADA, page.getContent().get(0).getEstado());
    }

    @Test
    @DisplayName("buscarPaginado_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos")
    void buscarPaginado_ConMasDeUnaPagina_DevuelveTotalElementsYTotalPagesCorrectos() {
        establecimiento = persistirEstablecimiento("paginado");
        for (int i = 1; i <= 5; i++) {
            persistirVenta(EstadoVenta.CONFIRMADA, LocalDateTime.of(2026, 1, i, 10, 0), BigDecimal.valueOf(1000L * i));
        }
        entityManager.flush();
        entityManager.clear();

        Page<Venta> primeraPagina = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(0, 2));

        assertEquals(5, primeraPagina.getTotalElements());
        assertEquals(3, primeraPagina.getTotalPages());
        assertEquals(2, primeraPagina.getContent().size());

        Page<Venta> ultimaPagina = ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 31, 23, 59, 59),
                PageRequest.of(2, 2));

        assertEquals(1, ultimaPagina.getContent().size());
    }
}
