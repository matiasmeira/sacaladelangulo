package com.matiasmeira.sacaladelangulo.cierrecaja.service;

import com.matiasmeira.sacaladelangulo.auth.model.PlanSuscripcion;
import com.matiasmeira.sacaladelangulo.auth.model.Role;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.auth.repository.UsuarioRepository;
import com.matiasmeira.sacaladelangulo.buffet.dto.DetalleVentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.dto.VentaRequest;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.buffet.service.VentaService;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.AbrirCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.dto.CerrarCajaRequest;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.MovimientoCajaRepository;
import com.matiasmeira.sacaladelangulo.cierrecaja.repository.TurnoCajaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.establecimiento.repository.EstablecimientoRepository;
import com.matiasmeira.sacaladelangulo.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests adversariales de Caja contra Postgres real (Testcontainers): concurrencia real de
 * apertura, y rollback transaccional real de una venta fallida. Requiere Docker (ver
 * AbstractPostgresIntegrationTest).
 */
@Tag("testcontainers")
@DisplayName("TurnoCajaService/VentaService - Postgres real (Testcontainers)")
class TurnoCajaConcurrenciaIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TurnoCajaService turnoCajaService;
    @Autowired
    private VentaService ventaService;
    @Autowired
    private UsuarioRepository usuarioRepository;
    @Autowired
    private EstablecimientoRepository establecimientoRepository;
    @Autowired
    private TurnoCajaRepository turnoCajaRepository;
    @Autowired
    private MovimientoCajaRepository movimientoCajaRepository;
    @Autowired
    private ProductoBuffetRepository productoBuffetRepository;
    @Autowired
    private VentaRepository ventaRepository;

    private Usuario dueno;
    private Establecimiento establecimiento;

    @BeforeEach
    void setUp() {
        dueno = usuarioRepository.save(Usuario.builder()
                .email("dueno-caja-" + System.nanoTime() + "@test.com")
                .password("hash")
                .nombre("Dueño Caja")
                .rol(Role.OWNER)
                .planSuscripcion(PlanSuscripcion.PREMIUM)
                .isActive(true)
                .emailVerified(true)
                .telefonoVerificado(false)
                .unsubscribeToken("tok-" + System.nanoTime())
                .build());

        establecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Club Caja")
                .direccion("Calle Falsa 456")
                .latitud(-34.6)
                .longitud(-58.4)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
    }

    @Test
    @DisplayName("abrirDosCajasEnParaleloParaElMismoEstablecimiento_SoloUnaGana")
    void abrirDosCajasEnParaleloParaElMismoEstablecimiento_SoloUnaGana() throws Exception {
        AbrirCajaRequest request = new AbrirCajaRequest(BigDecimal.valueOf(1000), null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Callable<Boolean> abrir = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                turnoCajaService.abrirCaja(establecimiento.getId(), request, dueno.getEmail());
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        };

        List<Future<Boolean>> futures = pool.invokeAll(List.of(abrir, abrir));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long exitos = futures.stream().filter(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count();

        assertEquals(1, exitos, "Solo una de las dos aperturas concurrentes debe haber ganado");
        assertEquals(1, turnoCajaRepository.findAll().stream()
                .filter(t -> t.getEstablecimiento().getId().equals(establecimiento.getId()))
                .count(), "Debe existir un único turno de caja persistido para el establecimiento");
    }

    @Test
    @DisplayName("cerrarCajaYaCerrada_Falla")
    void cerrarCajaYaCerrada_Falla() {
        Long turnoId = turnoCajaService.abrirCaja(
                establecimiento.getId(), new AbrirCajaRequest(BigDecimal.ZERO, null), dueno.getEmail()).id();

        CerrarCajaRequest cierre = new CerrarCajaRequest(BigDecimal.ZERO, "cierre de prueba");
        turnoCajaService.cerrarCaja(establecimiento.getId(), turnoId, cierre, dueno.getEmail());

        assertThrows(IllegalArgumentException.class, () ->
                turnoCajaService.cerrarCaja(establecimiento.getId(), turnoId, cierre, dueno.getEmail()));
    }

    /**
     * Rollback real: si falla la venta (2do producto del carrito no pertenece al
     * establecimiento), el stock ya descontado del 1er producto (dentro de la misma
     * transacción, antes del error) debe revertirse, y no debe quedar ni Venta ni
     * MovimientoCaja persistidos. Un test con repositorios mockeados no puede probar esto:
     * el mock no tiene semántica transaccional real.
     */
    @Test
    @DisplayName("ventaFallida_RollbackNoDejaStockNiVentaNiMovimientoPersistidos")
    void ventaFallida_RollbackNoDejaStockNiVentaNiMovimientoPersistidos() {
        ProductoBuffet productoValido = productoBuffetRepository.save(ProductoBuffet.builder()
                .nombre("Agua")
                .precio(BigDecimal.valueOf(500))
                .stock(10)
                .establecimiento(establecimiento)
                .build());

        // Producto de OTRO establecimiento: buscarProductoDelEstablecimiento lo rechaza,
        // pero recién después de que el producto válido ya sufrió el descuento de stock
        // dentro del mismo for (ver VentaService.registrarVenta).
        Establecimiento otroEstablecimiento = establecimientoRepository.save(Establecimiento.builder()
                .nombre("Otro club")
                .direccion("Otra calle")
                .latitud(-1.0)
                .longitud(-1.0)
                .requiereSena(false)
                .isActive(true)
                .dueno(dueno)
                .build());
        ProductoBuffet productoAjeno = productoBuffetRepository.save(ProductoBuffet.builder()
                .nombre("Gaseosa ajena")
                .precio(BigDecimal.valueOf(700))
                .stock(10)
                .establecimiento(otroEstablecimiento)
                .build());

        turnoCajaService.abrirCaja(establecimiento.getId(), new AbrirCajaRequest(BigDecimal.ZERO, null), dueno.getEmail());

        VentaRequest request = new VentaRequest(establecimiento.getId(), null, MetodoPago.EFECTIVO, List.of(
                new DetalleVentaRequest(productoValido.getId(), 2),
                new DetalleVentaRequest(productoAjeno.getId(), 1)
        ));

        assertThrows(IllegalArgumentException.class, () -> ventaService.registrarVenta(request, dueno.getEmail()));

        ProductoBuffet recargado = productoBuffetRepository.findById(productoValido.getId()).orElseThrow();
        assertEquals(10, recargado.getStock(), "El stock del producto válido debe quedar sin tocar tras el rollback");
        assertEquals(0, ventaRepository.count(), "No debe quedar ninguna Venta persistida");
        assertEquals(0, movimientoCajaRepository.findAll().size(), "No debe quedar ningún MovimientoCaja persistido");
    }

    /**
     * FIX aplicado (ver REVISION_FUNCIONAL.md, hallazgo de coherencia de caminos de plata):
     * VentaService.cancelarVenta / GastoService.editarGasto/eliminarGasto revertían el
     * movimiento de caja original llamando a TurnoCajaService.registrarMovimientoSiCorresponde,
     * que siempre escribía contra el turno ACTUALMENTE abierto — no contra el turno donde se
     * originó el movimiento que se estaba corrigiendo. Se optó por la opción "no compensar en
     * absoluto si el turno original ya cerró" (en vez de permitir escribir en un turno CERRADO):
     * TurnoCajaService.movimientoOriginalSigueEnTurnoAbierto ahora gatea la reversión, y tanto
     * VentaService como GastoService la saltean (con un log.warn) si el turno donde vive el
     * movimiento original ya no es el turno abierto actual. La diferencia queda documentada
     * solo en el propio Venta.estado/Gasto — que de todas formas es la fuente de verdad para
     * los reportes (ver ReporteGastosService, que lee de Gasto directo, no de MovimientoCaja).
     */
    @Test
    @DisplayName("compensarVentaCanceladaEnOtroTurno_NoEnsuciaElArqueoDeUnTurnoQueNoTuvoElMovimientoFisico")
    void compensarVentaCanceladaEnOtroTurno_NoDeberiaEnsuciarElArqueoDeUnTurnoAjeno() {
        ProductoBuffet producto = productoBuffetRepository.save(ProductoBuffet.builder()
                .nombre("Alfajor")
                .precio(BigDecimal.valueOf(1000))
                .stock(50)
                .establecimiento(establecimiento)
                .build());

        // Turno A: se vende en efectivo y se cierra con esa venta ya contabilizada.
        Long turnoAId = turnoCajaService.abrirCaja(
                establecimiento.getId(), new AbrirCajaRequest(BigDecimal.valueOf(5000), null), dueno.getEmail()).id();

        var venta = ventaService.registrarVenta(new VentaRequest(establecimiento.getId(), null, MetodoPago.EFECTIVO,
                List.of(new DetalleVentaRequest(producto.getId(), 1))), dueno.getEmail());

        turnoCajaService.cerrarCaja(establecimiento.getId(), turnoAId,
                new CerrarCajaRequest(BigDecimal.valueOf(6000), "cierre turno A"), dueno.getEmail());

        // Turno B: un turno totalmente nuevo, sin relación con la venta de arriba. Solo se
        // aporta el fondo inicial, ningún billete más entra ni sale físicamente.
        BigDecimal fondoInicialB = BigDecimal.valueOf(2000);
        Long turnoBId = turnoCajaService.abrirCaja(
                establecimiento.getId(), new AbrirCajaRequest(fondoInicialB, null), dueno.getEmail()).id();

        // Se cancela, en el turno B, una venta que en realidad pertenece al turno A (ya cerrado).
        ventaService.cancelarVenta(venta.id(), dueno.getEmail());

        var cierreB = turnoCajaService.cerrarCaja(establecimiento.getId(), turnoBId,
                new CerrarCajaRequest(fondoInicialB, "cierre turno B, nadie tocó la plata"), dueno.getEmail());

        // Expectativa correcta: como en el turno B no entró ni salió ningún billete real, su
        // saldo teórico en efectivo debería seguir siendo exactamente el fondo inicial.
        assertEquals(0, fondoInicialB.compareTo(cierreB.saldoTeoricoEfectivo()),
                "El arqueo del turno B no debería verse afectado por la cancelación de una venta que se cobró y cerró en el turno A");
    }
}
