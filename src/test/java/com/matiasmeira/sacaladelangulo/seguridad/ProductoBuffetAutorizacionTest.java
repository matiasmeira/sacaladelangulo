package com.matiasmeira.sacaladelangulo.seguridad;

import com.matiasmeira.sacaladelangulo.auth.model.PermisoEmpleado;
import com.matiasmeira.sacaladelangulo.auth.model.Usuario;
import com.matiasmeira.sacaladelangulo.buffet.model.ProductoBuffet;
import com.matiasmeira.sacaladelangulo.support.AbstractBuffetSecurityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catálogo del buffet: /api/v1/establecimientos/{e}/productos-buffet. POST, PUT, PATCH stock y DELETE son
 * OWNER/ADMIN por @PreAuthorize (ProductoBuffetController:29, :39, :50, :75) y el service autoriza con
 * validarPropietarioOAdmin (ProductoBuffetService:39, :64, :85, :122 -> AutorizacionEmpleadoService:135); el
 * empleado queda afuera aunque tenga todos los permisos. El GET (controller:67) admite también EMPLOYEE y el
 * service exige REGISTRAR_VENTA_BUFFET con validarAccion (ProductoBuffetService:113 -> :59).
 *
 * <p>Sólo se testean ids coherentes (producto del complejo del path): el cruce producto/path y el
 * establecimiento inexistente se resuelven antes de autorizar (ProductoBuffetService:128-140, pendiente 69,
 * abierto) y no se congelan acá.
 */
@DisplayName("Productos del buffet /api/v1/establecimientos/{e}/productos-buffet")
class ProductoBuffetAutorizacionTest extends AbstractBuffetSecurityTest {

    private static final String MENSAJE_403_ACCION = "No autorizado para realizar esta acción en este establecimiento";

    private String base() {
        return "/api/v1/establecimientos/" + establecimientoA.getId() + "/productos-buffet";
    }

    private Usuario empleadoTodosLosPermisos() {
        return empleado(establecimientoA, EnumSet.allOf(PermisoEmpleado.class));
    }

    private void assertAIntacto() {
        ProductoBuffet a = recargar(productoA);
        assertEquals("Agua A", a.getNombre());
        assertEquals(10, a.getStock());
        assertEquals(0, new BigDecimal("100").compareTo(a.getPrecio()));
        assertEquals("Agua B", recargar(productoB).getNombre());
    }

    private int cantidadEnA() {
        return productoBuffetRepository.findByEstablecimientoId(establecimientoA.getId()).size();
    }

    @Nested
    @DisplayName("POST")
    class Crear {
        private ResultActions crear(Usuario quien) throws Exception {
            return mockMvc.perform(post(base()).header("Authorization", bearer(quien))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\":\"Gaseosa\",\"precio\":250,\"stock\":7}"));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve201YPersiste")
        void duenoPropio() throws Exception {
            crear(duenoA).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.establecimientoId").value(establecimientoA.getId()));
            assertEquals(2, cantidadEnA());
        }

        @Test
        @DisplayName("admin_Devuelve201")
        void admin() throws Exception {
            crear(admin).andExpect(status().isCreated());
            assertEquals(2, cantidadEnA());
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403SinPersistir")
        void duenoAjeno() throws Exception {
            crear(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertEquals(1, cantidadEnA());
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            crear(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertEquals(1, cantidadEnA());
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            crear(empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertEquals(1, cantidadEnA());
        }
    }

    @Nested
    @DisplayName("PUT /{productoId}")
    class Actualizar {
        private ResultActions actualizar(Usuario quien) throws Exception {
            return mockMvc.perform(put(base() + "/" + productoA.getId()).header("Authorization", bearer(quien))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\":\"Renombrado\",\"precio\":300,\"stock\":99}"));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200ActualizaYNoTocaElStock")
        void duenoPropio() throws Exception {
            actualizar(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.nombre").value("Renombrado"));
            ProductoBuffet a = recargar(productoA);
            assertEquals("Renombrado", a.getNombre());
            assertEquals(10, a.getStock());
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            actualizar(admin).andExpect(status().isOk());
            assertEquals("Renombrado", recargar(productoA).getNombre());
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403SinCambios")
        void duenoAjeno() throws Exception {
            actualizar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertAIntacto();
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            actualizar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            actualizar(empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }
    }

    @Nested
    @DisplayName("PATCH /{productoId}/stock")
    class Stock {
        private ResultActions ajustar(Usuario quien) throws Exception {
            return mockMvc.perform(patch(base() + "/" + productoA.getId() + "/stock")
                    .header("Authorization", bearer(quien))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":5}"));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200YSumaStock")
        void duenoPropio() throws Exception {
            ajustar(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(15));
            assertEquals(15, recargar(productoA).getStock());
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            ajustar(admin).andExpect(status().isOk());
            assertEquals(15, recargar(productoA).getStock());
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403SinCambios")
        void duenoAjeno() throws Exception {
            ajustar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertAIntacto();
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            ajustar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            ajustar(empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }
    }

    @Nested
    @DisplayName("DELETE /{productoId}")
    class Eliminar {
        private ResultActions eliminar(Usuario quien) throws Exception {
            return mockMvc.perform(delete(base() + "/" + productoA.getId()).header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve204YBorraSoloElSuyo")
        void duenoPropio() throws Exception {
            eliminar(duenoA).andExpect(status().isNoContent());
            assertTrue(productoBuffetRepository.findById(productoA.getId()).isEmpty());
            assertTrue(productoBuffetRepository.findById(productoB.getId()).isPresent());
        }

        @Test
        @DisplayName("admin_Devuelve204")
        void admin() throws Exception {
            eliminar(admin).andExpect(status().isNoContent());
            assertTrue(productoBuffetRepository.findById(productoA.getId()).isEmpty());
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403SinBorrar")
        void duenoAjeno() throws Exception {
            eliminar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_SERVICE));
            assertAIntacto();
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            eliminar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }

        @Test
        @DisplayName("empleadoConTodosLosPermisos_Devuelve403PorAnotacion")
        void empleadoTodos() throws Exception {
            eliminar(empleadoTodosLosPermisos()).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
            assertAIntacto();
        }
    }

    @Nested
    @DisplayName("GET")
    class Listar {
        private ResultActions listar(Usuario quien) throws Exception {
            return mockMvc.perform(get(base()).header("Authorization", bearer(quien)));
        }

        @Test
        @DisplayName("duenoPropio_Devuelve200SoloConSusProductos")
        void duenoPropio() throws Exception {
            listar(duenoA).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].nombre").value("Agua A"));
        }

        @Test
        @DisplayName("admin_Devuelve200")
        void admin() throws Exception {
            listar(admin).andExpect(status().isOk()).andExpect(jsonPath("$[0].nombre").value("Agua A"));
        }

        @Test
        @DisplayName("duenoAjeno_Devuelve403")
        void duenoAjeno() throws Exception {
            listar(duenoB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("jugador_Devuelve403PorAnotacion")
        void jugador() throws Exception {
            listar(jugador).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(ERROR_PREAUTHORIZE));
        }

        @Test
        @DisplayName("empleadoConRegistrarVentaBuffet_Devuelve200")
        void empleadoConPermiso() throws Exception {
            Usuario vendedor = empleado(establecimientoA, Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET));
            listar(vendedor).andExpect(status().isOk()).andExpect(jsonPath("$[0].nombre").value("Agua A"));
        }

        @Test
        @DisplayName("empleadoSinPermiso_Devuelve403")
        void empleadoSinPermiso() throws Exception {
            listar(empleadoSinPermiso).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("empleadoConOtroPermisoPeroNoVentaBuffet_Devuelve403")
        void empleadoConOtroPermiso() throws Exception {
            // empleadoConPermiso tiene sólo OPERAR_CAJA
            listar(empleadoConPermiso).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }

        @Test
        @DisplayName("empleadoDeOtroEstablecimientoConPermiso_Devuelve403")
        void empleadoDeOtroEstablecimiento() throws Exception {
            Usuario deB = empleado(establecimientoB, Set.of(PermisoEmpleado.REGISTRAR_VENTA_BUFFET));
            listar(deB).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(MENSAJE_403_ACCION));
        }
    }
}
