package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.ProductoBuffetRepository;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractSecurityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/buffet/ventas: @PreAuthorize OWNER/ADMIN/EMPLOYEE (VentaBuffetController:46); el
 * jugador queda afuera ahí. El resto lo decide AutorizacionEmpleadoService.validarAccion
 * (línea 59) con REGISTRAR_VENTA_BUFFET, invocado desde VentaService.registrarVenta (línea 72)
 * sobre el establecimiento del BODY: dueño ajeno, empleado sin el permiso y empleado con permiso
 * pero apuntando a otro establecimiento reciben AccessDeniedException -> 403.
 *
 * <p>PUT /api/v1/buffet/ventas/{id}/cancelar: @PreAuthorize OWNER/ADMIN (VentaBuffetController:55),
 * sin excepción para empleados aunque tengan todos los permisos; el dueño ajeno lo rechaza
 * AutorizacionEmpleadoService.validarPropietarioOAdmin (línea 135), invocado desde
 * VentaService.cancelarVenta (línea 165).
 */
@DisplayName("Ventas del buffet")
class VentaBuffetAutorizacionTest extends AbstractSecurityWebTest {

    private static final String MENSAJE_403_ACCION = "No autorizado para realizar esta acción en este establecimiento";

    @Autowired
    private ProductoBuffetRepository productoBuffetRepository;

    @Autowired
    private VentaRepository ventaRepository;

    private ProductoBuffet productoA;
    private ProductoBuffet productoB;

    @BeforeEach
    void crearProductos() {
        productoA = producto(establecimientoA, "Agua A");
        productoB = producto(establecimientoB, "Agua B");
    }

    private ProductoBuffet producto(Establecimiento establecimiento, String nombre) {
        return productoBuffetRepository.save(ProductoBuffet.builder()
                .nombre(nombre)
                .precio(new BigDecimal("100"))
                .stock(10)
                .establecimiento(establecimiento)
                .build());
    }

    private int stock(ProductoBuffet producto) {
        return productoBuffetRepository.findById(producto.getId()).orElseThrow().getStock();
    }

    @Nested
    @DisplayName("POST /api/v1/buffet/ventas")
    class Registrar {

        private ResultActions registrar(Usuario usuario, Establecimiento establecimiento, ProductoBuffet producto) throws Exception {
            String body = "{\"establecimientoId\":" + establecimiento.getId()
                    + ",\"metodoPago\":\"EFECTIVO\",\"detalles\":[{\"productoId\":" + producto.getId() + ",\"cantidad\":2}]}";
            // Idempotency-Key es obligatorio en este POST (IdempotencyFilter.RUTAS_CLAVE_OBLIGATORIA)
            return mockMvc.perform(post("/api/v1/buffet/ventas")
                    .header("Authorization", bearer(usuario))
                    .header("Idempotency-Key", "venta-" + System.nanoTime())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            registrar(jugador, establecimientoA, productoA)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertEquals(0, ventaRepository.count());
            assertEquals(10, stock(productoA));
        }

        @Test
        @DisplayName("establecimientoInexistenteEnElBody_Devuelve403IgualAlAjeno")
        void establecimientoInexistenteEnElBody_Devuelve403() throws Exception {
            Establecimiento inexistente = Establecimiento.builder().id(987654321L).build();
            registrar(duenoA, inexistente, productoA)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
            assertEquals(0, ventaRepository.count());
            assertEquals(10, stock(productoA));
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimientoSobreElA_Devuelve403")
        void duenoDeOtroEstablecimientoSobreElA_Devuelve403() throws Exception {
            // AutorizacionEmpleadoService:59
            registrar(duenoB, establecimientoA, productoA)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
            assertEquals(0, ventaRepository.count());
            assertEquals(10, stock(productoA));
        }

        @Test
        @DisplayName("empleadoSinElPermiso_Devuelve403")
        void empleadoSinElPermiso_Devuelve403() throws Exception {
            // AutorizacionEmpleadoService:59 (empleadoConPermiso sólo tiene OPERAR_CAJA)
            registrar(empleadoConPermiso, establecimientoA, productoA)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
            assertEquals(0, ventaRepository.count());
            assertEquals(10, stock(productoA));
        }

        @Test
        @DisplayName("empleadoConElPermiso_Devuelve201YDescuentaStock")
        void empleadoConElPermiso_Devuelve201YDescuentaStock() throws Exception {
            Usuario empleadoDeA = empleado(establecimientoA, Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET));
            registrar(empleadoDeA, establecimientoA, productoA).andExpect(status().isCreated());
            assertEquals(1, ventaRepository.count());
            assertEquals(8, stock(productoA));
        }

        @Test
        @DisplayName("empleadoConElPermisoConElEstablecimientoDeOtroEnElBody_Devuelve403SinVentaEnB")
        void empleadoConElPermisoConElEstablecimientoDeOtroEnElBody_Devuelve403SinVentaEnB() throws Exception {
            Usuario empleadoDeA = empleado(establecimientoA, Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET));
            // AutorizacionEmpleadoService:117-123: el permiso vale sólo en el establecimiento del empleado
            registrar(empleadoDeA, establecimientoB, productoB)
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
            assertEquals(0, ventaRepository.count());
            assertEquals(10, stock(productoB));
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/buffet/ventas/{id}/cancelar")
    class Cancelar {

        private Venta venta;

        @BeforeEach
        void sembrarVentaConfirmada() {
            // Venta de 2 unidades de productoA; el stock queda en 8 como lo dejaría registrarVenta.
            productoA.setStock(8);
            productoBuffetRepository.save(productoA);
            Venta nueva = Venta.builder()
                    .establecimiento(establecimientoA)
                    .fechaHora(LocalDateTime.now())
                    .total(new BigDecimal("200"))
                    .estado(EstadoVenta.CONFIRMADA)
                    .metodoPago(MetodoPago.EFECTIVO)
                    .detalles(new ArrayList<>())
                    .build();
            nueva.getDetalles().add(DetalleVenta.builder()
                    .venta(nueva).productoBuffet(productoA).cantidad(2).subtotal(new BigDecimal("200")).build());
            venta = ventaRepository.save(nueva);
        }

        private ResultActions cancelar(Usuario usuario) throws Exception {
            return mockMvc.perform(put("/api/v1/buffet/ventas/" + venta.getId() + "/cancelar")
                    .header("Authorization", bearer(usuario)));
        }

        private void assertVenta(EstadoVenta estado, int stockEsperado) {
            assertEquals(estado, ventaRepository.findById(venta.getId()).orElseThrow().getEstado());
            assertEquals(stockEsperado, stock(productoA));
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoConTodosLosPermisos_Devuelve403PorAnotacion() throws Exception {
            Usuario todopoderoso = empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
            cancelar(todopoderoso).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertVenta(EstadoVenta.CONFIRMADA, 8);
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador_Devuelve403PorAnotacion() throws Exception {
            cancelar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertVenta(EstadoVenta.CONFIRMADA, 8);
        }

        @Test
        @DisplayName("duenoDeOtroEstablecimiento_Devuelve403")
        void duenoDeOtroEstablecimiento_Devuelve403() throws Exception {
            // AutorizacionEmpleadoService:135
            cancelar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("No autorizado en este establecimiento"));
            assertVenta(EstadoVenta.CONFIRMADA, 8);
        }

        @Test
        @DisplayName("duenoDelEstablecimiento_Devuelve200YDevuelveElStock")
        void duenoDelEstablecimiento_Devuelve200YDevuelveElStock() throws Exception {
            cancelar(duenoA).andExpect(status().isOk());
            assertVenta(EstadoVenta.CANCELADA, 10);
        }
    }
}
