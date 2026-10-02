package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.model.DetalleVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.EstadoVenta;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.buffet.model.Venta;
import com.matiasmeira.sacaladelangulo.buffet.repository.VentaRepository;
import com.matiasmeira.sacaladelangulo.core.pago.MetodoPago;
import com.matiasmeira.sacaladelangulo.establecimiento.model.Establecimiento;
import com.matiasmeira.sacaladelangulo.support.AbstractBuffetSecurityTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/buffet/ventas y /api/v1/buffet/ventas/metricas (consultas del dueño). @PreAuthorize OWNER/ADMIN
 * (VentaBuffetController:68 y :83): el empleado queda afuera aunque tenga todos los permisos.
 * VentaMetricasService autoriza con validarPropietarioOAdmin sobre el establecimientoId del query param
 * (líneas 53 y 87 -> AutorizacionEmpleadoService:135) ANTES de buscar nada (pendiente 69): un establecimiento
 * inexistente responde el mismo 403 que uno ajeno. Las ventas de A y B se siembran con total distinto
 * para aseverar que cada dueño ve sólo las de su complejo.
 */
@DisplayName("Consultas de ventas del buffet (listado y métricas)")
class VentaBuffetConsultaAutorizacionTest extends AbstractBuffetSecurityTest {

    @Autowired
    private VentaRepository ventaRepository;

    @BeforeEach
    void sembrarVentas() {
        venta(establecimientoA, productoA, "700");
        venta(establecimientoB, productoB, "900");
    }

    private void venta(Establecimiento e, ProductoBuffet producto, String total) {
        // La query de métricas hace JOIN FETCH de los detalles: una venta sin ítems no se cuenta.
        Venta venta = Venta.builder()
                .establecimiento(e).fechaHora(LocalDateTime.now())
                .total(new BigDecimal(total)).metodoPago(MetodoPago.EFECTIVO).estado(EstadoVenta.CONFIRMADA)
                .detalles(new ArrayList<>()).build();
        venta.getDetalles().add(DetalleVenta.builder()
                .venta(venta).productoBuffet(producto).cantidad(1).subtotal(new BigDecimal(total)).build());
        ventaRepository.save(venta);
    }

    private String query(Establecimiento e) {
        return "?establecimientoId=" + e.getId() + "&desde=" + LocalDate.now().minusDays(1)
                + "&hasta=" + LocalDate.now().plusDays(1);
    }

    private ResultActions listar(Usuario quien, Establecimiento e) throws Exception {
        return mockMvc.perform(get("/api/v1/buffet/ventas" + query(e)).header("Authorization", bearer(quien)));
    }

    private ResultActions metricas(Usuario quien, Establecimiento e) throws Exception {
        return mockMvc.perform(get("/api/v1/buffet/ventas/metricas" + query(e)).header("Authorization", bearer(quien)));
    }

    private Usuario empleadoTodos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    // ---- listado ----

    @Test
    @DisplayName("listar_duenoPropio_Devuelve200SoloConSusVentas")
    void listar_duenoPropio() throws Exception {
        listar(duenoA, establecimientoA).andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].total").value(700));
    }

    @Test
    @DisplayName("listar_admin_Devuelve200ParaCualquierComplejo")
    void listar_admin() throws Exception {
        listar(admin, establecimientoB).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].total").value(900));
    }

    @Test
    @DisplayName("establecimientoInexistente_Devuelve403IgualAlAjeno")
    void establecimientoInexistente_Devuelve403() throws Exception {
        Establecimiento inexistente = Establecimiento.builder().id(987654321L).build();
        listar(duenoA, inexistente).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
        metricas(duenoA, inexistente).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("establecimientoInexistenteComoAdmin_Devuelve404")
    void establecimientoInexistenteComoAdmin_Devuelve404() throws Exception {
        Establecimiento inexistente = Establecimiento.builder().id(987654321L).build();
        listar(admin, inexistente).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Establecimiento no encontrado"));
    }

    @Test
    @DisplayName("listar_duenoAjeno_Devuelve403")
    void listar_duenoAjeno() throws Exception {
        listar(duenoB, establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("listar_jugador_Devuelve403PorAnotacion")
    void listar_jugador() throws Exception {
        listar(jugador, establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("listar_empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
    void listar_empleadoTodos() throws Exception {
        listar(empleadoTodos(), establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    // ---- métricas ----

    @Test
    @DisplayName("metricas_duenoPropio_Devuelve200ConSuIngreso")
    void metricas_duenoPropio() throws Exception {
        metricas(duenoA, establecimientoA).andExpect(status().isOk()).andExpect(jsonPath("$.ingresoTotal").value(700));
    }

    @Test
    @DisplayName("metricas_admin_Devuelve200ParaCualquierComplejo")
    void metricas_admin() throws Exception {
        metricas(admin, establecimientoB).andExpect(status().isOk()).andExpect(jsonPath("$.ingresoTotal").value(900));
    }

    @Test
    @DisplayName("metricas_duenoAjeno_Devuelve403")
    void metricas_duenoAjeno() throws Exception {
        metricas(duenoB, establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
    }

    @Test
    @DisplayName("metricas_jugador_Devuelve403PorAnotacion")
    void metricas_jugador() throws Exception {
        metricas(jugador, establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }

    @Test
    @DisplayName("metricas_empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
    void metricas_empleadoTodos() throws Exception {
        metricas(empleadoTodos(), establecimientoA).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
    }
}
