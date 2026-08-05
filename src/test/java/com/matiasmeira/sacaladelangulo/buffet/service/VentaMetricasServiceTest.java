package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.dto.MetricasVentasResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extraído de VentaServiceTest (ver B12 en la auditoría): estos tests cubrían
 * obtenerMetricas cuando ese método todavía vivía en VentaService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VentaMetricasService - Tests de métricas de ventas de buffet")
class VentaMetricasServiceTest {

    @Mock
    private VentaRepository ventaRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @InjectMocks
    private VentaMetricasService ventaMetricasService;

    private Usuario dueno;
    private Establecimiento establecimiento;
    private ProductoBuffet agua;
    private ProductoBuffet alfajor;

    @BeforeEach
    void setUp() {
        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        establecimiento = Establecimiento.builder()
                .id(10L)
                .nombre("Establecimiento Test")
                .direccion("Calle Test 123")
                .latitud(-34.6037)
                .longitud(-58.3816)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        agua = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        alfajor = ProductoBuffet.builder()
                .id(2L)
                .nombre("Alfajor")
                .precio(BigDecimal.valueOf(800))
                .stock(10)
                .establecimiento(establecimiento)
                .build();
    }

    @Test
    @DisplayName("obtenerMetricas_Exito_CalculaIngresoCantidadYTicketPromedio")
    void obtenerMetricas_Exito_CalculaIngresoCantidadYTicketPromedio() {
        // Arrange: venta1 = 3 aguas (4500), venta2 = 1 agua + 1 alfajor (1500 + 800 = 2300)
        Venta venta1 = Venta.builder()
                .id(300L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.of(2026, 1, 10, 12, 0))
                .total(BigDecimal.valueOf(4500))
                .estado(EstadoVenta.CONFIRMADA)
                .metodoPago(MetodoPago.EFECTIVO)
                .detalles(List.of(DetalleVenta.builder()
                        .productoBuffet(agua).cantidad(3).subtotal(BigDecimal.valueOf(4500)).build()))
                .build();

        Venta venta2 = Venta.builder()
                .id(301L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.of(2026, 1, 15, 18, 0))
                .total(BigDecimal.valueOf(2300))
                .estado(EstadoVenta.CONFIRMADA)
                .metodoPago(MetodoPago.EFECTIVO)
                .detalles(List.of(
                        DetalleVenta.builder().productoBuffet(agua).cantidad(1).subtotal(BigDecimal.valueOf(1500)).build(),
                        DetalleVenta.builder().productoBuffet(alfajor).cantidad(1).subtotal(BigDecimal.valueOf(800)).build()
                ))
                .build();

        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(ventaRepository.findByEstablecimientoIdAndEstadoAndFechaHoraBetween(
                eq(establecimiento.getId()), eq(EstadoVenta.CONFIRMADA), any(), any()))
                .thenReturn(List.of(venta1, venta2));

        // Act
        MetricasVentasResponse response = ventaMetricasService.obtenerMetricas(establecimiento.getId(), desde, hasta, dueno.getEmail());

        // Assert
        assertEquals(0, BigDecimal.valueOf(6800).compareTo(response.ingresoTotal()));
        assertEquals(2L, response.cantidadVentas());
        assertEquals(0, BigDecimal.valueOf(3400).compareTo(response.ticketPromedio()));

        // Ranking: agua vendió 4 unidades en total (3+1), alfajor 1 -> agua primero
        assertEquals(2, response.productosMasVendidos().size());
        assertEquals(agua.getId(), response.productosMasVendidos().get(0).productoId());
        assertEquals(4L, response.productosMasVendidos().get(0).cantidadVendida());
        assertEquals(0, BigDecimal.valueOf(6000).compareTo(response.productosMasVendidos().get(0).ingresoGenerado()));
        assertEquals(alfajor.getId(), response.productosMasVendidos().get(1).productoId());
        assertEquals(1L, response.productosMasVendidos().get(1).cantidadVendida());

        // Verifica que solo se pidan ventas CONFIRMADA (las canceladas no deben sumar)
        verify(ventaRepository).findByEstablecimientoIdAndEstadoAndFechaHoraBetween(
                eq(establecimiento.getId()), eq(EstadoVenta.CONFIRMADA), any(), any());
    }

    @Test
    @DisplayName("obtenerMetricas_Exito_SinVentasDevuelveCeros")
    void obtenerMetricas_Exito_SinVentasDevuelveCeros() {
        // Arrange
        LocalDate desde = LocalDate.of(2026, 1, 1);
        LocalDate hasta = LocalDate.of(2026, 1, 31);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);
        when(ventaRepository.findByEstablecimientoIdAndEstadoAndFechaHoraBetween(
                eq(establecimiento.getId()), eq(EstadoVenta.CONFIRMADA), any(), any()))
                .thenReturn(List.of());

        // Act
        MetricasVentasResponse response = ventaMetricasService.obtenerMetricas(establecimiento.getId(), desde, hasta, dueno.getEmail());

        // Assert
        assertEquals(0, BigDecimal.ZERO.compareTo(response.ingresoTotal()));
        assertEquals(0L, response.cantidadVentas());
        assertEquals(0, BigDecimal.ZERO.compareTo(response.ticketPromedio()));
        assertTrue(response.productosMasVendidos().isEmpty());
    }

    @Test
    @DisplayName("obtenerMetricas_Fallo_DesdeMayorQueHasta")
    void obtenerMetricas_Fallo_DesdeMayorQueHasta() {
        // Arrange
        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, dueno.getEmail())).thenReturn(dueno);

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> ventaMetricasService.obtenerMetricas(
                        establecimiento.getId(), LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), dueno.getEmail())
        );
    }

    @Test
    @DisplayName("obtenerMetricas_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void obtenerMetricas_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .rol(Role.OWNER)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarPropietarioOAdmin(establecimiento, otroDueno.getEmail()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> ventaMetricasService.obtenerMetricas(
                        establecimiento.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), otroDueno.getEmail())
        );
    }
}
