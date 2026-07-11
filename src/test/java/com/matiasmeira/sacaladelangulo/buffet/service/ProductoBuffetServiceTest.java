package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.dto.AjustarStockRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetMapper;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetResponse;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductoBuffetService - Tests de inventario de buffet")
class ProductoBuffetServiceTest {

    @Mock
    private ProductoBuffetRepository productoBuffetRepository;

    @Mock
    private EstablecimientoRepository establecimientoRepository;

    @Mock
    private UsuarioRepository usuarioRepository;

    private ProductoBuffetService productoBuffetService;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        productoBuffetService = new ProductoBuffetService(
                productoBuffetRepository, establecimientoRepository, usuarioRepository, new ProductoBuffetMapper());

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
    }

    @Test
    @DisplayName("crearProducto_Exito")
    void crearProducto_Exito() {
        // Arrange
        ProductoBuffetRequest request = new ProductoBuffetRequest("Agua mineral", "500ml", BigDecimal.valueOf(1500), 20);

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(productoBuffetRepository.save(any(ProductoBuffet.class))).thenAnswer(invocation -> {
            ProductoBuffet producto = invocation.getArgument(0);
            producto.setId(1L);
            return producto;
        });

        // Act
        ProductoBuffetResponse response = assertDoesNotThrow(
                () -> productoBuffetService.crearProducto(establecimiento.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals("Agua mineral", response.nombre());
        assertEquals(20, response.stock());
        assertEquals(establecimiento.getId(), response.establecimientoId());
        verify(productoBuffetRepository).save(any(ProductoBuffet.class));
    }

    @Test
    @DisplayName("crearProducto_Fallo_UsuarioNoEsDuenoDelEstablecimiento")
    void crearProducto_Fallo_UsuarioNoEsDuenoDelEstablecimiento() {
        // Arrange
        ProductoBuffetRequest request = new ProductoBuffetRequest("Agua mineral", "500ml", BigDecimal.valueOf(1500), 20);

        Usuario otroDueno = Usuario.builder()
                .id(3L)
                .email("otro-dueno@test.com")
                .rol(Role.OWNER)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(otroDueno.getEmail())).thenReturn(Optional.of(otroDueno));

        // Act & Assert
        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> productoBuffetService.crearProducto(establecimiento.getId(), request, otroDueno.getEmail())
        );
        verify(productoBuffetRepository, never()).save(any());
    }

    @Test
    @DisplayName("actualizarProducto_Exito_NoModificaStock")
    void actualizarProducto_Exito_NoModificaStock() {
        // Arrange: el request trae stock=99, pero actualizarProducto no debe tocar el stock
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .descripcion("500ml")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        ProductoBuffetRequest request = new ProductoBuffetRequest("Agua con gas", "500ml, con gas", BigDecimal.valueOf(1800), 99);

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(productoBuffetRepository.save(any(ProductoBuffet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ProductoBuffetResponse response = assertDoesNotThrow(
                () -> productoBuffetService.actualizarProducto(establecimiento.getId(), producto.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals("Agua con gas", response.nombre());
        assertEquals(BigDecimal.valueOf(1800), response.precio());
        assertEquals(20, response.stock());
    }

    @Test
    @DisplayName("ajustarStock_Exito_Suma")
    void ajustarStock_Exito_Suma() {
        // Arrange
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        AjustarStockRequest request = new AjustarStockRequest(10);

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(productoBuffetRepository.save(any(ProductoBuffet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ProductoBuffetResponse response = assertDoesNotThrow(
                () -> productoBuffetService.ajustarStock(establecimiento.getId(), producto.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals(30, response.stock());
    }

    @Test
    @DisplayName("ajustarStock_Exito_Resta")
    void ajustarStock_Exito_Resta() {
        // Arrange
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        AjustarStockRequest request = new AjustarStockRequest(-5);

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(productoBuffetRepository.save(any(ProductoBuffet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ProductoBuffetResponse response = assertDoesNotThrow(
                () -> productoBuffetService.ajustarStock(establecimiento.getId(), producto.getId(), request, dueno.getEmail()));

        // Assert
        assertEquals(15, response.stock());
    }

    @Test
    @DisplayName("ajustarStock_Fallo_StockInsuficiente")
    void ajustarStock_Fallo_StockInsuficiente() {
        // Arrange
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(5)
                .establecimiento(establecimiento)
                .build();

        AjustarStockRequest request = new AjustarStockRequest(-10);

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act & Assert
        assertThrows(
                IllegalArgumentException.class,
                () -> productoBuffetService.ajustarStock(establecimiento.getId(), producto.getId(), request, dueno.getEmail())
        );
        verify(productoBuffetRepository, never()).save(any());
    }

    @Test
    @DisplayName("listarPorEstablecimiento_Exito")
    void listarPorEstablecimiento_Exito() {
        // Arrange
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        when(establecimientoRepository.findById(establecimiento.getId())).thenReturn(Optional.of(establecimiento));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));
        when(productoBuffetRepository.findByEstablecimientoId(establecimiento.getId())).thenReturn(List.of(producto));

        // Act
        List<ProductoBuffetResponse> response = assertDoesNotThrow(
                () -> productoBuffetService.listarPorEstablecimiento(establecimiento.getId(), dueno.getEmail()));

        // Assert
        assertEquals(1, response.size());
        assertEquals("Agua mineral", response.get(0).nombre());
    }

    @Test
    @DisplayName("eliminarProducto_Exito")
    void eliminarProducto_Exito() {
        // Arrange
        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(establecimiento)
                .build();

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(usuarioRepository.findByEmail(dueno.getEmail())).thenReturn(Optional.of(dueno));

        // Act
        assertDoesNotThrow(() -> productoBuffetService.eliminarProducto(establecimiento.getId(), producto.getId(), dueno.getEmail()));

        // Assert
        verify(productoBuffetRepository).delete(producto);
    }

    @Test
    @DisplayName("eliminarProducto_Fallo_ProductoNoPerteneceAlEstablecimiento")
    void eliminarProducto_Fallo_ProductoNoPerteneceAlEstablecimiento() {
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

        ProductoBuffet producto = ProductoBuffet.builder()
                .id(1L)
                .nombre("Agua mineral")
                .precio(BigDecimal.valueOf(1500))
                .stock(20)
                .establecimiento(otroEstablecimiento)
                .build();

        when(productoBuffetRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        // Act & Assert: se pide eliminar pasando el establecimiento equivocado
        assertThrows(
                IllegalArgumentException.class,
                () -> productoBuffetService.eliminarProducto(establecimiento.getId(), producto.getId(), dueno.getEmail())
        );
        verify(productoBuffetRepository, never()).delete(any());
    }
}
