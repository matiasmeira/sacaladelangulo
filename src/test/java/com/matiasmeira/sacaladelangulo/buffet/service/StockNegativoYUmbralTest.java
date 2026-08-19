package com.matiasmeira.sacaladelangulo.buffet.service;

import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.dto.AjustarStockRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.DetalleVentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.ProductoBuffetResponse;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El stock de buffet es INFORMATIVO, no una condición para vender.
 *
 * V12 tenía un CHECK stock >= 0 que bloqueaba ventas reales —ya cobradas en el
 * mostrador— sólo porque el inventario del sistema estaba desactualizado, y el
 * cajero recibía un "conflicto de integridad" sin poder cerrar la operación.
 * V16 lo saca: si el producto se vendió, se registra; el número en negativo es
 * la señal de que hay que reponer o corregir la carga.
 *
 * Fija además el umbral de alerta por producto, que antes era un número fijo en
 * el frontend, igual para todos: no se repone igual una caja de agua que rota
 * por decenas que un producto que sale una vez por semana.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-stock-negativo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.secret=TestSecretKeyQueSeaSuficientementeLargaParaValidarElTest123",
        "spring.config.import=",
        "spring.flyway.enabled=false"
})
@DisplayName("Buffet - stock negativo permitido y umbral de alerta por producto")
class StockNegativoYUmbralTest {

    private static final String EMAIL_DUENO = "dueno@stock-test.com";

    @Autowired
    private ProductoBuffetService productoBuffetService;

    @Autowired
    private VentaService ventaService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private EstablecimientoRepository establecimientoRepository;

    // static: JUnit crea una instancia nueva por método, y los tres comparten la
    // misma base H2. Sin esto, cada uno reintenta sembrar el mismo dueño y choca
    // contra el índice único de email.
    private static Long establecimientoId;

    @BeforeEach
    void sembrar() {
        if (establecimientoId != null) return;

        Usuario dueno = usuarioRepository.save(Usuario.builder()
                .email(EMAIL_DUENO)
                .password("hash")
                .nombre("Dueno Test")
                .rol(Role.OWNER)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(true)
                .build());

        establecimientoId = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Complejo Stock")
                .direccion("Calle Falsa 123")
                .slug("complejo-stock-test")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build()).getId();
    }

    private ProductoBuffetResponse crearProducto(String nombre, int stock, Integer umbral) {
        return productoBuffetService.crearProducto(
                establecimientoId,
                new ProductoBuffetRequest(nombre, null, new BigDecimal("1000"), stock, umbral),
                EMAIL_DUENO);
    }

    @Test
    @DisplayName("venderMasDeLoQueHay_DejaElStockNegativo_NoRechazaLaVenta")
    void venderMasDeLoQueHay_DejaElStockNegativo_NoRechazaLaVenta() {
        ProductoBuffetResponse producto = crearProducto("Agua", 2, null);

        // Se vendieron 5 aunque el sistema decía 2: la venta ocurrió de verdad.
        ventaService.registrarVenta(
                new VentaRequest(establecimientoId, null, MetodoPago.EFECTIVO,
                        List.of(new DetalleVentaRequest(producto.id(), 5))),
                EMAIL_DUENO);

        List<ProductoBuffetResponse> productos =
                productoBuffetService.listarPorEstablecimiento(establecimientoId, EMAIL_DUENO);
        ProductoBuffetResponse despues = productos.stream()
                .filter(p -> p.id().equals(producto.id())).findFirst().orElseThrow();

        assertThat(despues.stock())
                .as("el stock queda negativo en vez de bloquear la venta")
                .isEqualTo(-3);
    }

    @Test
    @DisplayName("ajustarStockDesdeNegativo_Funciona_ParaPoderCorregirlo")
    void ajustarStockDesdeNegativo_Funciona_ParaPoderCorregirlo() {
        ProductoBuffetResponse producto = crearProducto("Gaseosa", 1, null);

        ventaService.registrarVenta(
                new VentaRequest(establecimientoId, null, MetodoPago.EFECTIVO,
                        List.of(new DetalleVentaRequest(producto.id(), 4))),
                EMAIL_DUENO);

        // Reponer parcialmente: sigue negativo, y aun así tiene que poder hacerse.
        ProductoBuffetResponse tras = productoBuffetService.ajustarStock(
                establecimientoId, producto.id(), new AjustarStockRequest(1), EMAIL_DUENO);

        assertThat(tras.stock()).isEqualTo(-2);
    }

    @Test
    @DisplayName("umbralAlerta_SeGuardaPorProducto_YCaeEn5SiNoSeEnvia")
    void umbralAlerta_SeGuardaPorProducto_YCaeEn5SiNoSeEnvia() {
        assertThat(crearProducto("Cerveza", 50, 12).umbralAlerta())
                .as("cada producto define el suyo")
                .isEqualTo(12);

        assertThat(crearProducto("Alfajor", 10, null).umbralAlerta())
                .as("sin enviarlo queda en el default histórico del frontend")
                .isEqualTo(5);
    }
}
