package com.matiasmeira.sacaladelangulo.buffet.repository;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import com.matiasmeira.sacaladelangulo.support.Establecimientos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Mismo bug de fondo que GastoRepositoryIntegrationTest (SQLState 42P18), en el sitio que
 * alimenta /panel/pagos vía GET /api/v1/buffet/ventas: VentaRepository.buscarPaginado con
 * estado=null -- el default real del panel ("todas") -- rompía contra Postgres antes del
 * cast. El @DataJpaTest existente (VentaRepositoryTest) corre contra H2 y no lo detecta.
 *
 * <p>Para ver el rojo: revertir el cast(...) de la condición IS NULL en
 * VentaRepository.buscarPaginado (dejarla como ":estado IS NULL") y volver a correr esta
 * clase con Docker disponible.
 */
@Tag("testcontainers")
@DisplayName("VentaRepository.buscarPaginado - estado=null contra Postgres real")
class VentaRepositoryNullEstadoIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private VentaRepository ventaRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private UsuarioRepository usuarioRepository;

    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-ventas-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño Ventas")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.TRIAL)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + System.nanoTime())
                .build());

        establecimiento = establecimientoRepository.save(Establecimientos.establecimientoOperativo(b -> b
                .nombre("Complejo Ventas")
                .direccion("Calle Ventas 123")
                .slug("complejo-ventas-" + System.nanoTime())
                .dueno(dueno)));
    }

    @Test
    @DisplayName("buscarPaginado con estado=null (default 'todas' del panel) no rompe y trae la venta")
    void buscarPaginado_EstadoNull_NoRompeYTraeLaVenta() {
        ventaRepository.save(Venta.builder()
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.now())
                .total(BigDecimal.valueOf(5000))
                .estado(EstadoVenta.CONFIRMADA)
                .metodoPago(MetodoPago.EFECTIVO)
                .build());

        Pageable pageable = PageRequest.of(0, 20);

        Page<Venta> resultado = assertDoesNotThrow(() -> ventaRepository.buscarPaginado(
                establecimiento.getId(), null,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
                pageable));

        assertEquals(1, resultado.getTotalElements());
    }
}
