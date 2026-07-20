package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.empleado.service.AutorizacionEmpleadoService;
import com.matiasmeira.sacaladelangulo.empleado.service.RegistroAuditoriaService;
import com.matiasmeira.sacaladelangulo.buffet.dto.DetalleVentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Cancha;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Deporte;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.reserva.model.EstadoReserva;
import com.matiasmeira.sacaladelangulo.reserva.model.Reserva;
import com.matiasmeira.sacaladelangulo.reserva.repository.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VentaService - Tests de ventas de buffet")
class VentaServiceTest {

    @Mock
    private VentaRepository ventaRepository;

    @Mock
    private ProductoBuffetRepository productoBuffetRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private ReservaRepository reservaRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    @Mock
    private AutorizacionEmpleadoService autorizacionEmpleadoService;

    @Mock
    private RegistroAuditoriaService registroAuditoriaService;

    private VentaService ventaService;

    private Usuario dueno;
    private Establecimiento establecimiento;
    private ProductoBuffet agua;
    private ProductoBuffet alfajor;

    @BeforeEach
    void setUp() {
        ventaService = new VentaService(
                ventaRepository, productoBuffetRepository, establecimientoRepository,
                reservaRepository, usuarioRepository, new VentaMapper(),
                autorizacionEmpleadoService, registroAuditoriaService);

        dueno = Usuario.builder()
                .id(2L)
                .email("dueno@test.com")
                .rol(Role.OWNER)
                .build();

        // Default: cualquier llamada a registrarVenta pasa la autorización como si la
        // hiciera el dueño real. Los tests que necesiten otro comportamiento lo overridean.
        lenient().when(autorizacionEmpleadoService.validarAccion(any(), any(), any())).thenReturn(dueno);

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
    @DisplayName("registrarVenta_Exito_UnProducto")
    void registrarVenta_Exito_UnProducto() {
        // Arrange
        VentaRequest request = new VentaRequest(establecimiento.getId(), null,
                List.of(new DetalleVentaRequest(agua.getId(), 3)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(100L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, dueno.getEmail()));

        // Assert
        assertEquals(BigDecimal.valueOf(4500), response.total());
        assertEquals(1, response.detalles().size());
        assertEquals(3, response.detalles().get(0).cantidad());
        assertEquals(0, BigDecimal.valueOf(1500).compareTo(response.detalles().get(0).precioUnitario()));
        assertEquals("CONFIRMADA", response.estado());
        assertEquals(17, agua.getStock());
        verify(productoBuffetRepository).save(agua);
    }

    @Test
    @DisplayName("registrarVenta_Exito_VariosProductos_SumaTotalCorrectamente")
    void registrarVenta_Exito_VariosProductos_SumaTotalCorrectamente() {
        // Arrange: 2 aguas (1500 c/u) + 1 alfajor (800) = 3800
        VentaRequest request = new VentaRequest(establecimiento.getId(), null, List.of(
                new DetalleVentaRequest(agua.getId(), 2),
                new DetalleVentaRequest(alfajor.getId(), 1)
        ));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));
        when(productoBuffetRepository.findById(alfajor.getId())).thenReturn(Optional.of(alfajor));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(101L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, dueno.getEmail()));

        // Assert
        assertEquals(BigDecimal.valueOf(3800), response.total());
        assertEquals(2, response.detalles().size());
        assertEquals(18, agua.getStock());
        assertEquals(9, alfajor.getStock());
    }

    @Test
    @DisplayName("registrarVenta_Exito_StockQuedaNegativoSiNoAlcanza")
    void registrarVenta_Exito_StockQuedaNegativoSiNoAlcanza() {
        // Arrange: se piden 25 aguas, hay 20 (ej. el dueño no cargó el stock inicial a tiempo)
        VentaRequest request = new VentaRequest(establecimiento.getId(), null,
                List.of(new DetalleVentaRequest(agua.getId(), 25)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(103L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, dueno.getEmail()));

        // Assert: la venta se registra igual y el stock queda en negativo
        assertEquals("CONFIRMADA", response.estado());
        assertEquals(-5, agua.getStock());
        verify(productoBuffetRepository).save(agua);
    }

    @Test
    @DisplayName("registrarVenta_Exito_VariosProductosConUnoQuedandoEnStockNegativo")
    void registrarVenta_Exito_VariosProductosConUnoQuedandoEnStockNegativo() {
        // Arrange: agua tiene stock de sobra, alfajor no alcanza pero igual se procesa
        VentaRequest request = new VentaRequest(establecimiento.getId(), null, List.of(
                new DetalleVentaRequest(agua.getId(), 1),
                new DetalleVentaRequest(alfajor.getId(), 12)
        ));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));
        when(productoBuffetRepository.findById(alfajor.getId())).thenReturn(Optional.of(alfajor));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(104L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, dueno.getEmail()));

        // Assert
        assertEquals(2, response.detalles().size());
        assertEquals(19, agua.getStock());
        assertEquals(-2, alfajor.getStock());
    }

    @Test
    @DisplayName("registrarVenta_Exito_EmpleadoConPermisoRegistrarVentaBuffet")
    void registrarVenta_Exito_EmpleadoConPermisoRegistrarVentaBuffet() {
        // Arrange
        Usuario empleado = Usuario.builder()
                .id(9L)
                .email("empleado-uuid@empleados.interno")
                .nombre("Empleado Mostrador")
                .rol(Role.EMPLOYEE)
                .establecimiento(establecimiento)
                .permisos(Set.of(com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.REGISTRAR_VENTA_BUFFET))
                .isActive(true)
                .build();

        VentaRequest request = new VentaRequest(establecimiento.getId(), null,
                List.of(new DetalleVentaRequest(agua.getId(), 2)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(establecimiento, empleado.getEmail(),
                com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado.REGISTRAR_VENTA_BUFFET)).thenReturn(empleado);
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(105L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, empleado.getEmail()));

        // Assert
        assertEquals("CONFIRMADA", response.estado());
        verify(registroAuditoriaService).registrar(
                eq(empleado), eq(com.matiasmeira.sacaladelangulo.empleado.model.AccionAuditoria.REGISTRAR_VENTA_BUFFET),
                eq(105L), eq(true), any());
    }

    @Test
    @DisplayName("registrarVenta_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void registrarVenta_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        VentaRequest request = new VentaRequest(establecimiento.getId(), null,
                List.of(new DetalleVentaRequest(agua.getId(), 1)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(autorizacionEmpleadoService.validarAccion(any(), any(), any()))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("No autorizado en este establecimiento"));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> ventaService.registrarVenta(request, "otro-dueno@test.com")
        );
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVenta_Fallo_ProductoNoPerteneceAlEstablecimiento")
    void registrarVenta_Fallo_ProductoNoPerteneceAlEstablecimiento() {
        // Arrange
        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(99L)
                .nombre("Otro")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        ProductoBuffet productoDeOtroEstablecimiento = ProductoBuffet.builder()
                .id(5L)
                .nombre("Gaseosa")
                .precio(BigDecimal.valueOf(1200))
                .stock(10)
                .establecimiento(otroEstablecimiento)
                .build();

        VentaRequest request = new VentaRequest(establecimiento.getId(), null,
                List.of(new DetalleVentaRequest(productoDeOtroEstablecimiento.getId(), 1)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(productoBuffetRepository.findById(productoDeOtroEstablecimiento.getId()))
                .thenReturn(Optional.of(productoDeOtroEstablecimiento));

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> ventaService.registrarVenta(request, dueno.getEmail())
        );
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVenta_Fallo_ReservaNoPerteneceAlEstablecimiento")
    void registrarVenta_Fallo_ReservaNoPerteneceAlEstablecimiento() {
        // Arrange
        Establecimiento otroEstablecimiento = Establecimiento.builder()
                .id(99L)
                .nombre("Otro")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .dueno(dueno)
                .requiereSena(true)
                .isActive(true)
                .build();

        Cancha canchaDeOtroEstablecimiento = Cancha.builder()
                .id(50L)
                .nombre("Cancha Otro Predio")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(otroEstablecimiento)
                .isActive(true)
                .build();

        Reserva reservaDeOtroEstablecimiento = Reserva.builder()
                .id(70L)
                .cancha(canchaDeOtroEstablecimiento)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        VentaRequest request = new VentaRequest(establecimiento.getId(), reservaDeOtroEstablecimiento.getId(),
                List.of(new DetalleVentaRequest(agua.getId(), 1)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(reservaRepository.findById(reservaDeOtroEstablecimiento.getId())).thenReturn(Optional.of(reservaDeOtroEstablecimiento));

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> ventaService.registrarVenta(request, dueno.getEmail())
        );
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVenta_Fallo_ReservaCanceladaOPendienteDeSena")
    void registrarVenta_Fallo_ReservaCanceladaOPendienteDeSena() {
        // Arrange: cargar consumo a una reserva CANCELADA no debe permitirse (ver M19)
        Cancha cancha = Cancha.builder()
                .id(60L)
                .nombre("Cancha A")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(establecimiento)
                .isActive(true)
                .build();

        Reserva reservaCancelada = Reserva.builder()
                .id(71L)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CANCELADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        VentaRequest request = new VentaRequest(establecimiento.getId(), reservaCancelada.getId(),
                List.of(new DetalleVentaRequest(agua.getId(), 1)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(reservaRepository.findById(reservaCancelada.getId())).thenReturn(Optional.of(reservaCancelada));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ventaService.registrarVenta(request, dueno.getEmail())
        );
        assertTrue(exception.getMessage().contains("CANCELADA"));
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("registrarVenta_Exito_ConReservaValidaQuedaAsociada")
    void registrarVenta_Exito_ConReservaValidaQuedaAsociada() {
        // Arrange
        Cancha cancha = Cancha.builder()
                .id(60L)
                .nombre("Cancha A")
                .deportes(Set.of(Deporte.FUTBOL))
                .capacidad(10)
                .precioBase(BigDecimal.valueOf(1500))
                .montoSena(BigDecimal.valueOf(500))
                .establecimiento(establecimiento)
                .isActive(true)
                .build();

        Reserva reserva = Reserva.builder()
                .id(80L)
                .cancha(cancha)
                .fechaHoraInicio(LocalDateTime.of(2030, 1, 15, 10, 0))
                .fechaHoraFin(LocalDateTime.of(2030, 1, 15, 11, 0))
                .estado(EstadoReserva.CONFIRMADA)
                .precioTotal(BigDecimal.valueOf(1500))
                .senaPagada(BigDecimal.ZERO)
                .build();

        VentaRequest request = new VentaRequest(establecimiento.getId(), reserva.getId(),
                List.of(new DetalleVentaRequest(agua.getId(), 1)));

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(reservaRepository.findById(reserva.getId())).thenReturn(Optional.of(reserva));
        when(productoBuffetRepository.findById(agua.getId())).thenReturn(Optional.of(agua));

        ArgumentCaptor<Venta> ventaCaptor = ArgumentCaptor.forClass(Venta.class);
        when(ventaRepository.save(ventaCaptor.capture())).thenAnswer(invocation -> {
            Venta venta = invocation.getArgument(0);
            venta.setId(102L);
            return venta;
        });

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.registrarVenta(request, dueno.getEmail()));

        // Assert
        assertEquals(reserva.getId(), response.reservaId());
        assertEquals(reserva.getId(), ventaCaptor.getValue().getReserva().getId());
    }

    @Test
    @DisplayName("cancelarVenta_Exito_RestauraStock")
    void cancelarVenta_Exito_RestauraStock() {
        // Arrange: venta ya confirmada de 3 aguas y 2 alfajores; al cancelar, el stock vuelve
        agua.setStock(17);
        alfajor.setStock(8);

        DetalleVenta detalleAgua = DetalleVenta.builder()
                .id(1L)
                .productoBuffet(agua)
                .cantidad(3)
                .subtotal(BigDecimal.valueOf(4500))
                .build();
        DetalleVenta detalleAlfajor = DetalleVenta.builder()
                .id(2L)
                .productoBuffet(alfajor)
                .cantidad(2)
                .subtotal(BigDecimal.valueOf(1600))
                .build();

        Venta venta = Venta.builder()
                .id(200L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.now())
                .total(BigDecimal.valueOf(6100))
                .estado(EstadoVenta.CONFIRMADA)
                .detalles(List.of(detalleAgua, detalleAlfajor))
                .build();

        when(ventaRepository.findByIdConDetalles(venta.getId())).thenReturn(Optional.of(venta));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(ventaRepository.save(any(Venta.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        VentaResponse response = assertDoesNotThrow(() -> ventaService.cancelarVenta(venta.getId(), dueno.getEmail()));

        // Assert
        assertEquals("CANCELADA", response.estado());
        assertEquals(20, agua.getStock());
        assertEquals(10, alfajor.getStock());
        verify(productoBuffetRepository).save(agua);
        verify(productoBuffetRepository).save(alfajor);
    }

    @Test
    @DisplayName("cancelarVenta_Exito_EsIdempotenteSiYaEstabaCancelada")
    void cancelarVenta_Exito_EsIdempotenteSiYaEstabaCancelada() {
        // Arrange
        DetalleVenta detalle = DetalleVenta.builder()
                .id(1L)
                .productoBuffet(agua)
                .cantidad(3)
                .subtotal(BigDecimal.valueOf(4500))
                .build();

        Venta ventaYaCancelada = Venta.builder()
                .id(201L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.now())
                .total(BigDecimal.valueOf(4500))
                .estado(EstadoVenta.CANCELADA)
                .detalles(List.of(detalle))
                .build();

        when(ventaRepository.findByIdConDetalles(ventaYaCancelada.getId())).thenReturn(Optional.of(ventaYaCancelada));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        VentaResponse response = assertDoesNotThrow(
                () -> ventaService.cancelarVenta(ventaYaCancelada.getId(), dueno.getEmail()));

        // Assert: no se vuelve a restaurar el stock ni se guarda de nuevo
        assertEquals("CANCELADA", response.estado());
        verify(productoBuffetRepository, never()).save(any());
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelarVenta_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void cancelarVenta_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        Venta venta = Venta.builder()
                .id(202L)
                .establecimiento(establecimiento)
                .fechaHora(LocalDateTime.now())
                .total(BigDecimal.valueOf(1500))
                .estado(EstadoVenta.CONFIRMADA)
                .detalles(List.of())
                .build();

        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .rol(Role.OWNER)
                .build();

        when(ventaRepository.findByIdConDetalles(venta.getId())).thenReturn(Optional.of(venta));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> ventaService.cancelarVenta(venta.getId(), otroDueno.getEmail())
        );
        verify(productoBuffetRepository, never()).save(any());
        verify(ventaRepository, never()).save(any());
    }

    @Test
    @DisplayName("cancelarVenta_Fallo_VentaNoExiste")
    void cancelarVenta_Fallo_VentaNoExiste() {
        // Arrange
        when(ventaRepository.findByIdConDetalles(999L)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(
                com.matiasmeira.sacaladelangulo.core.exception.EntityNotFoundException.class,
                () -> ventaService.cancelarVenta(999L, dueno.getEmail())
        );
    }

}
